#!/usr/bin/env python3
"""Run the pinned BGE artifact on arm64 Android without replacing the live application.

This measures actual MNN embeddings on synthetic data. It is not an end-to-end memory-answer score.
"""
import argparse
import hashlib
import json
import pathlib
import statistics
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
REMOTE = '/data/local/tmp/matrix-embedding-probe'

def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def digest(path):
    return hashlib.file_digest(open(path, 'rb'), 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--ndk', type=pathlib.Path, required=True)
    parser.add_argument('--model-dir', type=pathlib.Path, required=True)
    parser.add_argument('--native-dir', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    corpus_path = ROOT / 'tools/evaluation/fixtures/semantic-recall-v1.json'
    corpus = json.loads(corpus_path.read_text())
    manifest_path = ROOT / 'matrix-agent-service/src/main/assets/embedding/bge-small-zh-v1.5.json'
    manifest = json.loads(manifest_path.read_text())
    for part in manifest['files']:
        file = args.model_dir / part['name']
        if file.stat().st_size != part['bytes'] or digest(file) != part['sha256']:
            raise ValueError('artifact mismatch: ' + part['name'])
    compiler = next((args.ndk / 'toolchains/llvm/prebuilt').glob('*/bin/aarch64-linux-android28-clang++'))
    with tempfile.TemporaryDirectory(prefix='matrix-embedding-') as temporary:
        temp = pathlib.Path(temporary)
        binary = temp / 'matrix_embedding_probe'
        run([compiler, '-std=c++17', '-O2', '-static-libstdc++',
             '-I'+str(ROOT/'ondevice/src/main/cpp/MNN/include'),
             '-I'+str(ROOT/'ondevice/src/main/cpp/MNN/transformers/llm/engine/include'),
             ROOT/'tools/evaluation/embedding_probe.cpp', '-L'+str(args.native_dir), '-lMNN',
             '-Wl,-rpath,$ORIGIN', '-o', binary])
        documents = [item['key']+' '+item['value'] for item in corpus['documents']]
        queries = ['为这个句子生成表示以用于检索相关文章：'+item['text'] for item in corpus['queries']]
        inputs = temp/'inputs.txt'
        inputs.write_text('\n'.join(documents+queries)+'\n')
        adb = ['adb','-s',args.serial]
        run(adb+['shell','mkdir','-p',REMOTE], stdout=subprocess.DEVNULL)
        files = [args.model_dir/item['name'] for item in manifest['files']]
        run(adb+['push',*files,args.native_dir/'libMNN.so',binary,inputs,REMOTE+'/'], stdout=subprocess.DEVNULL)
        run(adb+['shell','chmod','755',REMOTE+'/matrix_embedding_probe'])
        raw = run(adb+['shell',f'cd {REMOTE} && LD_LIBRARY_PATH=. ./matrix_embedding_probe config.json inputs.txt'], capture_output=True, text=True).stdout
        lines = [json.loads(line) for line in raw.splitlines() if line.startswith('{')]
        summary = next(line for line in lines if line.get('summary'))
        samples = [line for line in lines if 'vector' in line]
        if len(samples) != len(documents)+len(queries):
            raise ValueError('incomplete native output')
        scored = []
        for query, encoded in zip(corpus['queries'],samples[len(documents):]):
            scores = [(corpus['documents'][i]['key'],sum(a*b for a,b in zip(encoded['vector'],doc['vector'])))
                      for i,doc in enumerate(samples[:len(documents)])]
            scores.sort(key=lambda item:(-item[1],item[0]))
            scored.append({**query,'scores':[{'key':key,'cosine':score} for key,score in scores]})
        metrics = []
        for threshold in [.5,.55,.6,.65,.7,.75,.8,.85,.9]:
            for split in ['calibration','holdout']:
                positive = [q for q in scored if q['split']==split and q['expected']]
                negative = [q for q in scored if q['split']==split and not q['expected']]
                row = {'threshold':threshold,'split':split,'positiveCount':len(positive),'negativeCount':len(negative)}
                for k in [1,3,5]:
                    row[f'recall@{k}'] = sum(bool(set(q['expected']) & {h['key'] for h in q['scores'][:k] if h['cosine']>=threshold}) for q in positive)/len(positive)
                row['negativeFalseRecallRate'] = sum(q['scores'][0]['cosine']>=threshold for q in negative)/len(negative)
                metrics.append(row)
        latency = sorted(row['millis'] for row in samples)
        report = {'schemaVersion':1,'complete':True,'syntheticOnly':True,'createdAtUnix':time.time(),
                  'serial':args.serial,'scope':'native embedding retrieval only; application/Room/answer accuracy measured separately',
                  'corpusSha256':digest(corpus_path),'manifestSha256':digest(manifest_path),'modelVersion':manifest['modelVersion'],
                  'librarySha256':digest(args.native_dir/'libMNN.so'),'resource':summary,
                  'latencyMillis':{'p50':statistics.median(latency),'p95':latency[min(len(latency)-1,int(len(latency)*.95))],
                                   'max':max(latency),'samples':len(latency)},'metrics':metrics,'queries':scored}
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        args.output.with_suffix('.native.json').write_text(json.dumps(lines,ensure_ascii=False)+'\n')
        print(json.dumps({'resource':summary,'latencyMillis':report['latencyMillis'],'threshold065':[m for m in metrics if m['threshold']==.65]},ensure_ascii=False))

if __name__ == '__main__':
    main()
