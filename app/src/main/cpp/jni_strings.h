// Textes entre Kotlin et llama.cpp : UTF-16 côté Java, UTF-8 côté llama.cpp.
// NewStringUTF et GetStringUTFChars parlent l'UTF-8 « modifié » de Java, où un emoji s'écrit en deux
// moitiés de 3 octets : Android accepte aussi l'UTF-8 ordinaire, la JVM de l'ordinateur (banc d'Axo)
// non, et les emojis y deviennent illisibles. On convertit donc nous-mêmes, pareil partout.
#pragma once

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

inline void append_utf8(std::string &out, uint32_t c) {
    if (c < 0x80) {
        out += (char) c;
    } else if (c < 0x800) {
        out += (char) (0xC0 | (c >> 6));
        out += (char) (0x80 | (c & 0x3F));
    } else if (c < 0x10000) {
        out += (char) (0xE0 | (c >> 12));
        out += (char) (0x80 | ((c >> 6) & 0x3F));
        out += (char) (0x80 | (c & 0x3F));
    } else {
        out += (char) (0xF0 | (c >> 18));
        out += (char) (0x80 | ((c >> 12) & 0x3F));
        out += (char) (0x80 | ((c >> 6) & 0x3F));
        out += (char) (0x80 | (c & 0x3F));
    }
}

// Texte Java -> UTF-8. Une moitié d'emoji seule devient U+FFFD.
inline std::string to_string(JNIEnv *env, jstring s) {
    const jsize n = env->GetStringLength(s);
    const jchar *chars = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(n);
    for (jsize i = 0; i < n; i++) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[++i] - 0xDC00);
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        append_utf8(out, c);
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

// UTF-8 -> texte Java. Un octet qui ne forme pas un caractère devient U+FFFD.
inline jstring to_jstring(JNIEnv *env, const std::string &s) {
    std::vector<jchar> out;
    out.reserve(s.size());
    const auto *b = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        uint32_t c = b[i];
        const int len = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 0;
        bool ok = len > 0 && i + len <= n;
        for (int k = 1; ok && k < len; k++) ok = (b[i + k] & 0xC0) == 0x80;
        if (!ok) {
            out.push_back(0xFFFD);
            i++;
            continue;
        }
        if (len == 2) c = ((c & 0x1F) << 6) | (b[i + 1] & 0x3F);
        else if (len == 3) c = ((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F);
        else if (len == 4) c = ((c & 0x07) << 18) | ((b[i + 1] & 0x3F) << 12) | ((b[i + 2] & 0x3F) << 6) | (b[i + 3] & 0x3F);
        if (c > 0x10FFFF) {
            out.push_back(0xFFFD);
        } else if (c >= 0x10000) {
            c -= 0x10000;
            out.push_back((jchar) (0xD800 + (c >> 10)));
            out.push_back((jchar) (0xDC00 + (c & 0x3FF)));
        } else {
            out.push_back((jchar) c);
        }
        i += len;
    }
    const jchar none = 0;
    return env->NewString(out.empty() ? &none : out.data(), (jsize) out.size());
}
