#include <jni.h>
#include <memory>
#include <string>
#include <llm/llm.hpp>

using MNN::Transformer::Embedding;

extern "C" JNIEXPORT jlong JNICALL
Java_com_matrix_agent_ondevice_mnn_MnnOnDeviceEmbedder_nativeLoad(
        JNIEnv* env, jclass, jstring configPath, jint expectedDimension) {
    if (!configPath) return 0;
    const char* chars = env->GetStringUTFChars(configPath, nullptr);
    if (!chars) return 0;
    std::string path(chars);
    env->ReleaseStringUTFChars(configPath, chars);
    try {
        std::unique_ptr<Embedding> model(Embedding::createEmbedding(path, false));
        if (!model || !model->set_config("{\"backend_type\":\"cpu\",\"thread_num\":2,\"precision\":\"normal\",\"memory\":\"low\"}")
                || !model->load() || model->dim() != expectedDimension) return 0;
        return reinterpret_cast<jlong>(model.release());
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matrix_agent_ondevice_mnn_MnnOnDeviceEmbedder_nativeEncode(
        JNIEnv* env, jclass, jlong handle, jbyteArray utf8, jint maxTokens) {
    if (!handle || !utf8 || maxTokens < 2 || maxTokens > 512) return nullptr;
    auto* model = reinterpret_cast<Embedding*>(handle);
    jsize length = env->GetArrayLength(utf8);
    if (length <= 0 || length > 32768) return nullptr;
    std::string text(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(utf8, 0, length, reinterpret_cast<jbyte*>(text.data()));
    if (env->ExceptionCheck()) return nullptr;
    try {
        // This adapter admits BERT CLS pooling artifacts only; the verified tokenizer owns token IDs.
        auto ids = model->tokenizer_encode("[CLS]" + text + "[SEP]");
        if (ids.size() < 2) return nullptr;
        if (ids.size() > static_cast<size_t>(maxTokens)) {
            auto separator = ids.back();
            ids.resize(maxTokens);
            ids.back() = separator;
        }
        auto result = model->ids_embedding(ids);
        if (result.get() == nullptr || !result->getInfo() || result->getInfo()->size != model->dim()) return nullptr;
        auto* data = result->readMap<float>();
        if (!data) return nullptr;
        jfloatArray output = env->NewFloatArray(model->dim());
        if (output) env->SetFloatArrayRegion(output, 0, model->dim(), data);
        return output;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_matrix_agent_ondevice_mnn_MnnOnDeviceEmbedder_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Embedding*>(handle);
}
