#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace matrix {
// Returns zero only for an incomplete suffix. Malformed, overlong and surrogate encodings fail closed.
inline size_t decodeUtf8(const std::string& bytes, size_t at, uint32_t& codepoint) {
    const auto first = static_cast<uint8_t>(bytes[at]);
    size_t count;
    if (first < 0x80) { codepoint = first; return 1; }
    if (first >= 0xc2 && first <= 0xdf) { count = 2; codepoint = first & 0x1f; }
    else if (first >= 0xe0 && first <= 0xef) { count = 3; codepoint = first & 0x0f; }
    else if (first >= 0xf0 && first <= 0xf4) { count = 4; codepoint = first & 7; }
    else throw std::invalid_argument("invalid UTF-8 lead byte");
    for (size_t i = 1; i < count; ++i) {
        if (at + i >= bytes.size()) return 0;
        const auto value = static_cast<uint8_t>(bytes[at + i]);
        if ((value & 0xc0) != 0x80) throw std::invalid_argument("invalid UTF-8 continuation");
        codepoint = (codepoint << 6) | (value & 0x3f);
    }
    if ((count == 2 && codepoint < 0x80) || (count == 3 && codepoint < 0x800)
            || (count == 4 && codepoint < 0x10000) || codepoint > 0x10ffff
            || (codepoint >= 0xd800 && codepoint <= 0xdfff)) throw std::invalid_argument("invalid UTF-8 scalar");
    return count;
}
inline std::string takeCompleteUtf8(std::string& pending) {
    size_t consumed = 0;
    while (consumed < pending.size()) {
        uint32_t scalar = 0;
        size_t count = decodeUtf8(pending, consumed, scalar);
        if (count == 0) break;
        consumed += count;
    }
    std::string complete = pending.substr(0, consumed);
    pending.erase(0, consumed);
    return complete;
}
inline std::u16string utf16(const std::string& text) {
    std::u16string result;
    for (size_t at = 0; at < text.size();) {
        uint32_t scalar = 0;
        size_t count = decodeUtf8(text, at, scalar);
        if (count == 0) throw std::invalid_argument("incomplete UTF-8 scalar");
        at += count;
        if (scalar <= 0xffff) result.push_back(static_cast<char16_t>(scalar));
        else {
            scalar -= 0x10000;
            result.push_back(static_cast<char16_t>(0xd800 + (scalar >> 10)));
            result.push_back(static_cast<char16_t>(0xdc00 + (scalar & 0x3ff)));
        }
    }
    return result;
}
// Retain a suffix that may become the end marker after the next model fragment.
inline std::string takeBeforeEndMarker(std::string& pending, const std::string& marker, bool final, bool& ended) {
    const auto at = pending.find(marker);
    if (at != std::string::npos) {
        auto text = pending.substr(0, at); pending.clear(); ended = true; return text;
    }
    size_t retained = 0;
    if (!final) for (size_t n = 1; n < marker.size() && n <= pending.size(); ++n) {
        if (pending.compare(pending.size() - n, n, marker, 0, n) == 0) retained = n;
    }
    auto text = pending.substr(0, pending.size() - retained);
    pending.erase(0, pending.size() - retained); return text;
}
inline std::string utf8(const std::u16string& text) {
    std::string result;
    for (size_t i = 0; i < text.size(); ++i) {
        uint32_t scalar = text[i];
        if (scalar >= 0xd800 && scalar <= 0xdbff) {
            if (++i == text.size() || text[i] < 0xdc00 || text[i] > 0xdfff) throw std::invalid_argument("invalid UTF-16 pair");
            scalar = 0x10000 + ((scalar - 0xd800) << 10) + (text[i] - 0xdc00);
        } else if (scalar >= 0xdc00 && scalar <= 0xdfff) throw std::invalid_argument("unpaired UTF-16 surrogate");
        if (scalar < 0x80) result += static_cast<char>(scalar);
        else if (scalar < 0x800) {
            result += static_cast<char>(0xc0 | (scalar >> 6)); result += static_cast<char>(0x80 | (scalar & 63));
        } else if (scalar < 0x10000) {
            result += static_cast<char>(0xe0 | (scalar >> 12)); result += static_cast<char>(0x80 | ((scalar >> 6) & 63)); result += static_cast<char>(0x80 | (scalar & 63));
        } else {
            result += static_cast<char>(0xf0 | (scalar >> 18)); result += static_cast<char>(0x80 | ((scalar >> 12) & 63));
            result += static_cast<char>(0x80 | ((scalar >> 6) & 63)); result += static_cast<char>(0x80 | (scalar & 63));
        }
    }
    return result;
}

}
