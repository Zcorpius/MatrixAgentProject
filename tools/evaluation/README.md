# 智能层验证与模型制品

所有质量语料均为合成数据；真实模型配置留在 `.model-evaluation/`，不要提交凭据。全仓门禁见 `./gradlew verifyArchitecture`，模型评测见 [evaluation.md](../../docs/evaluation.md)。真机报告见 [实施记录](../../docs/智能层实施记录-2026-09-27.md)。

## BGE 制品

来源为 [BAAI/bge-small-zh-v1.5 的固定 revision](https://huggingface.co/BAAI/bge-small-zh-v1.5/tree/7999e1d3359715c523056ef9478215996d62a620)，模型卡标记 MIT。原始文件大小和 SHA-256 保存在 `fixtures/embedding-source-v1.json`；实际 MNN 制品清单在 `matrix-agent-service/src/main/assets/embedding/bge-small-zh-v1.5.json`。部署时应保留上游许可。

转换使用 Python 3.11.15、PyTorch 2.7.1、transformers 4.51.3、MNN 3.2.0 和本仓库随附的 MNN exporter。固定输入共六个文件（含 safetensors，不读取 pickle 权重）；输出为 int8 权重、bf16 embedding、512 维 CLS 向量。MNN 运行端再次 L2 归一化，查询前缀固定为 `为这个句子生成表示以用于检索相关文章：`，文档不加前缀。

在已经安装上述转换依赖的隔离环境中，从仓库根执行：

```sh
python ondevice/src/main/cpp/MNN/transformers/llm/export/llmexport.py \
  --path .model-evaluation/embedding/bge-small-zh-v1.5 \
  --tokenizer_path .model-evaluation/embedding/bge-small-zh-v1.5 \
  --dst_path .model-evaluation/embedding/bge-mnn --export mnn --quant_bit 8
```

转换输出受工具版本影响，重导出后必须逐文件与制品清单比对；字节不同不能绕过校验。需要切换制品时，升级 `modelVersion`、维护新清单，并重跑固定的校准/保留集。阈值 0.50 只用 calibration 集选择，holdout 不参与调参；两组各 12 条正例和 6 条困难负例。

可选的正式分发方式是将这六个已校验文件打入签名 APK，首次编码时经有界流、SHA 校验、fsync 和原子改名安装到 Host 私有模型目录：

```sh
./gradlew :matrix-agent-service:assembleRelease \
  -Pmatrix.embeddingArtifactDir=.model-evaluation/embedding/bge-mnn
```

该参数同样适用于 debug。模型总大小 37,196,223 字节，权重不进入 Git。未指定参数时构建不携带权重，且会清理上次生成的模型 assets；Host 可以继续使用已经安装的相同制品。安装与校验在独立有界线程执行；单次查询超时只停止等待，不中断正在进行的资产复制，关闭图时才取消安装。没有有效制品、哈希不符或推理不可用时，混合模式返回现有词面结果，不伪造向量。更换模型版本不会原地替换已在用的 native 文件。

## 真机探针

使用设备序列号替换 `SERIAL`，安装仅用 `adb install -r`。system-UID 应用不要运行 instrumentation APK 或清除数据；这些探针只包含在 debug，广播需要 DUMP 权限，长操作由非导出的前台服务承载。

```sh
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver --ez intelligence_probe true
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver --ez model_feature_probe true
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver --ez reflection_probe true
```

`intelligence_probe` 使用独立合成 Room 库测试迁移、CAS、删除/清除和管道取消，并在 Android JSON 运行时验证显式 null 流式终止字段、研究摘要转义后的字符/UTF-8 双预算与多来源保留、约 8 MiB/4000 块附件的 BM25 文末召回与耗时。打包模型冷安装测试使用独立临时目录；JNI 检索对照使用固定 12 条事实/36 条查询。用于外部制品安装验证的六个文件需先推送到 app 外部文件目录下 `embedding-import/`。`model_feature_probe` 调用当前保存的真实模型，覆盖云流式、端侧流式、附件五类问答与记忆回答对照；没有已下载聊天模型时会通过现有模型市场下载 Qwen3-0.6B-MNN，不改变当前默认模型。`reflection_probe` 对预先选定的四例按两轮 ABBA 顺序采样，16 次案例计分；仅从实际 FAILED/PARTIALLY_SUCCEEDED 训练轨迹学习，8 秒超时或不充分证据保留 UNKNOWN。默认不开启生产反思。`--ez memory_feature_probe true` 可独立运行 24 次记忆回答对照；评分要求正例实际成功读取对应 key，拒答中列举候选不计为答对。该探针沿用 model-feature-device.json 输出名，重跑前应归档原报告。

研究探针创建一条标题为“合成验证：多来源研究摘要”的真实一次性计划，明确允许联网，经生产调度与通知交付：

```sh
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver --es research_probe start_long
adb -s SERIAL shell am broadcast -n com.matrix.agent/.debug.MediaProbeReceiver --es research_probe status
```

可用控制命令为 `recover / cancel / revoke / expire / exhaust_models / exhaust_active / cleanup`。后三类耗尽/到期及 revoke 是仅针对该探针合成 run 的故障注入，不能将其计数当成真实已发出的调用数。`cleanup` 删除合成计划。每次开始新场景前先 cleanup，等待 `MatrixResearchProbe command=cleanup`，再归档外部 `research-device.json`；广播返回只表示投递，不能当成操作完成。进程恢复场景使用 `am force-stop com.matrix.agent` 后显式发送 recover 唤醒，不卸载应用。

可用脚本串行执行同样的故障/恢复流程（不要与其他长探针并发运行）：

```sh
python3 tools/evaluation/research_device_probe.py --serial SERIAL \
  --scenario faults --output .model-evaluation/research-faults
python3 tools/evaluation/research_device_probe.py --serial SERIAL \
  --scenario recovery --output .model-evaluation/research-recovery
```

恢复脚本会明确停止 Host 90 秒，报告把停机时间单列，不能冒称 90 秒连续推理。

报告位于 `/sdcard/Android/data/com.matrix.agent/files/verification/`。保留未通过的首轮和中断报告；`complete=true` 只表示采样结束，仍需检查各项 passed、质量指标与交付状态。不得把目标达成、Engine SUCCEEDED、引用编号校验通过混为一谈。

## 原生独立测试

```sh
python3 tools/evaluation/check_native_stream.py
python3 tools/evaluation/embedding_device_probe.py \
  --serial SERIAL --ndk /absolute/path/to/ndk \
  --model-dir .model-evaluation/embedding/bge-mnn \
  --native-dir /absolute/path/to/arm64-native-libs \
  --output .model-evaluation/embedding-native-report.json
```

原生 probe 测量的仅是编码、余弦检索与进程峰值内存；应用侧召回、prompt 投影、真实回答和 UI 首次可见时间分别统计。MNN 聊天与 embedding 在生产中共用串行 native lane；不能将独立 CLI 的无竞争延迟冒称交互 SLA。
