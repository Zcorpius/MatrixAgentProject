#include <llm/llm.hpp>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sys/resource.h>
using MNN::Transformer::Embedding;
using Clock = std::chrono::steady_clock;
static double millis(Clock::time_point start) { return std::chrono::duration<double,std::milli>(Clock::now()-start).count(); }
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    auto start = Clock::now();
    std::unique_ptr<Embedding> model(Embedding::createEmbedding(argv[1], false));
    if (!model || !model->set_config("{\"backend_type\":\"cpu\",\"thread_num\":2,\"precision\":\"normal\",\"memory\":\"low\"}") || !model->load()) return 3;
    double load = millis(start);
    std::ifstream inputs(argv[2]);
    std::string text;
    int index = 0;
    std::cout << std::setprecision(9);
    while (std::getline(inputs, text)) {
        start = Clock::now();
        auto ids = model->tokenizer_encode("[CLS]" + text + "[SEP]");
        if (ids.size() > 512) { auto separator = ids.back(); ids.resize(512); ids.back() = separator; }
        auto result = model->ids_embedding(ids);
        if (!result.get() || !result->getInfo() || result->getInfo()->size != model->dim()) return 4;
        auto data = result->readMap<float>();
        double squared = 0;
        for (int i=0; i<model->dim(); ++i) { if (!std::isfinite(data[i])) return 5; squared += data[i]*data[i]; }
        if (squared < 1e-12) return 6;
        double elapsed = millis(start);
        std::cout << "{\"index\":" << index++ << ",\"tokens\":" << ids.size() << ",\"millis\":" << elapsed << ",\"vector\":[";
        for (int i=0; i<model->dim(); ++i) { if (i) std::cout << ','; std::cout << data[i]/std::sqrt(squared); }
        std::cout << "]}" << std::endl;
    }
    struct rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    std::cout << "{\"summary\":true,\"dimension\":" << model->dim() << ",\"loadMillis\":" << load << ",\"maxRssKiB\":" << usage.ru_maxrss << "}" << std::endl;
}
