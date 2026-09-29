#include "utf8stream.hpp"
#include <cassert>
int main() {
    const std::string text = u8"中文🚘\nA";
    for (size_t split = 0; split <= text.size(); ++split) {
        std::string pending = text.substr(0, split);
        auto a = matrix::takeCompleteUtf8(pending);
        pending += text.substr(split);
        auto b = matrix::takeCompleteUtf8(pending);
        assert(pending.empty());
        assert(a + b == text);
        assert(matrix::utf16(a + b) == u"中文🚘\nA");
    }
    std::string pending, recovered;
    for (char byte : text) { pending += byte; recovered += matrix::takeCompleteUtf8(pending); }
    assert(recovered == text && pending.empty());
    for (const std::string& bad : {std::string("\xc0\xaf"), std::string("\xed\xa0\x80"),
            std::string("\xf4\x90\x80\x80"), std::string("\x80"), std::string("\xe4\x41\x80")}) {
        bool threw = false;
        try { matrix::utf16(bad); } catch (const std::invalid_argument&) { threw = true; }
        assert(threw);
    }
    assert(matrix::utf16(std::string("a\0b", 3)).size() == 3);
    assert(matrix::utf8(matrix::utf16(text)) == text);
    for (const std::u16string& bad : {std::u16string{0xd800}, std::u16string{0xdc00},
            std::u16string{0xd800, 'A'}, std::u16string{'A', 0xdc00}}) {
        bool threw = false;
        try { matrix::utf8(bad); } catch (const std::invalid_argument&) { threw = true; }
        assert(threw);
    }
    const std::string marked = text + "<eop>";
    for (size_t split = 0; split <= marked.size(); ++split) {
        std::string buffer = marked.substr(0, split); bool ended = false;
        auto first = matrix::takeBeforeEndMarker(buffer, "<eop>", false, ended);
        buffer += marked.substr(split);
        auto second = matrix::takeBeforeEndMarker(buffer, "<eop>", true, ended);
        assert(first + second == text && ended && buffer.empty());
    }
}
