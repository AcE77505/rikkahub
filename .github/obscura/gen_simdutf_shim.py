#!/usr/bin/env python3
"""生成 simdutf__* 的 C 垫片。

背景：deno 的 rusty_v8 在启用 simdutf 特性时，Rust 侧 extern "C" 声明的是
`simdutf__xxx`（双下划线），而 Android 预编译静态库里的 simdutf.o 只导出 C++
实现 `simdutf::xxx`。本脚本读 simdutf.o 的符号表，把 `simdutf::<name>` 的
Itanium mangled 名找出来，生成一层 C++ 转发函数。

usage: gen_simdutf_shim.py <simdutf.syms> <simdutf_shim.cc>
"""
import re
import sys

# (name, ret, [(type, argname), ...])
U8, U16, U32 = "const uint8_t*", "const uint16_t*", "const uint32_t*"
M8, M16, M32 = "uint8_t*", "uint16_t*", "uint32_t*"
SZ, Z = "size_t", "usize"  # usize 只用于打印

FUNCS = [
    ("validate_utf8", "bool", [(U8, "buf"), (SZ, "len")]),
    ("validate_utf8_with_errors", "FfiResult", [(U8, "buf"), (SZ, "len")]),
    ("validate_ascii", "bool", [(U8, "buf"), (SZ, "len")]),
    ("validate_ascii_with_errors", "FfiResult", [(U8, "buf"), (SZ, "len")]),
    ("validate_utf16le", "bool", [(U16, "buf"), (SZ, "len")]),
    ("validate_utf16le_with_errors", "FfiResult", [(U16, "buf"), (SZ, "len")]),
    ("validate_utf16be", "bool", [(U16, "buf"), (SZ, "len")]),
    ("validate_utf16be_with_errors", "FfiResult", [(U16, "buf"), (SZ, "len")]),
    ("validate_utf32", "bool", [(U32, "buf"), (SZ, "len")]),
    ("validate_utf32_with_errors", "FfiResult", [(U32, "buf"), (SZ, "len")]),
    ("convert_utf8_to_utf16le", SZ, [(U8, "in"), (SZ, "len"), (M16, "out")]),
    ("convert_utf8_to_utf16le_with_errors", "FfiResult", [(U8, "in"), (SZ, "len"), (M16, "out")]),
    ("convert_valid_utf8_to_utf16le", SZ, [(U8, "in"), (SZ, "len"), (M16, "out")]),
    ("convert_utf16le_to_utf8", SZ, [(U16, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_utf16le_to_utf8_with_errors", "FfiResult", [(U16, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_valid_utf16le_to_utf8", SZ, [(U16, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_utf8_to_utf16be", SZ, [(U8, "in"), (SZ, "len"), (M16, "out")]),
    ("convert_utf16be_to_utf8", SZ, [(U16, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_utf8_to_latin1", SZ, [(U8, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_utf8_to_latin1_with_errors", "FfiResult", [(U8, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_valid_utf8_to_latin1", SZ, [(U8, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_latin1_to_utf8", SZ, [(U8, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_latin1_to_utf16le", SZ, [(U8, "in"), (SZ, "len"), (M16, "out")]),
    ("convert_utf16le_to_latin1", SZ, [(U16, "in"), (SZ, "len"), (M8, "out")]),
    ("convert_utf8_to_utf32", SZ, [(U8, "in"), (SZ, "len"), (M32, "out")]),
    ("convert_utf32_to_utf8", SZ, [(U32, "in"), (SZ, "len"), (M8, "out")]),
    ("utf8_length_from_utf16le", SZ, [(U16, "in"), (SZ, "len")]),
    ("utf8_length_from_utf16be", SZ, [(U16, "in"), (SZ, "len")]),
    ("utf16_length_from_utf8", SZ, [(U8, "in"), (SZ, "len")]),
    ("utf8_length_from_latin1", SZ, [(U8, "in"), (SZ, "len")]),
    ("latin1_length_from_utf8", SZ, [(U8, "in"), (SZ, "len")]),
    ("utf32_length_from_utf8", SZ, [(U8, "in"), (SZ, "len")]),
    ("utf8_length_from_utf32", SZ, [(U32, "in"), (SZ, "len")]),
    ("utf16_length_from_utf32", SZ, [(U32, "in"), (SZ, "len")]),
    ("utf32_length_from_utf16le", SZ, [(U16, "in"), (SZ, "len")]),
    ("count_utf8", SZ, [(U8, "in"), (SZ, "len")]),
    ("count_utf16le", SZ, [(U16, "in"), (SZ, "len")]),
    ("count_utf16be", SZ, [(U16, "in"), (SZ, "len")]),
    ("detect_encodings", "int32_t", [(U8, "in"), (SZ, "len")]),
    ("maximal_binary_length_from_base64", SZ, [(U8, "in"), (SZ, "len")]),
    ("base64_to_binary", "FfiResult", [(U8, "in"), (SZ, "len"), (M8, "out"),
                                       ("uint64_t", "opt"), ("uint64_t", "last")]),
    ("base64_length_from_binary", SZ, [(SZ, "len"), ("uint64_t", "opt")]),
    ("binary_to_base64", SZ, [(U8, "in"), (SZ, "len"), (M8, "out"), ("uint64_t", "opt")]),
]


def find_mangled(syms, name):
    pat = re.compile(r"_ZN7simdutf" + str(len(name)) + name + "E")
    hits = [line.split()[-1] for line in syms if pat.search(line)]
    # 去重，保留最短（重载时优先无额外参数的那个）
    return sorted(set(hits), key=len)


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: gen_simdutf_shim.py <simdutf.syms> <simdutf_shim.cc>")
    syms = open(sys.argv[1]).read().splitlines()
    out = []
    out.append("// 自动生成：deno rusty_v8 的 simdutf__* C 垫片 -> v8 静态库内 simdutf.o 的 C++ 实现")
    out.append("#include <cstdint>")
    out.append("#include <cstddef>")
    out.append("#include <cstdio>")
    out.append("#include <cstdlib>")
    out.append("#include <cstdarg>")
    out.append("")
    out.append("struct FfiResult { int32_t error; size_t count; };")
    out.append("")
    out.append("// simdutf.o 是 Chromium 的 libc++（std::__Cr）编的，只需要这一个符号")
    out.append('extern "C" void cr_verbose_abort(const char* fmt, ...)')
    out.append('    __asm__("_ZNSt4__Cr22__libcpp_verbose_abortEPKcz");')
    out.append("")
    out.append("namespace {")
    missing = []
    decls = []
    for name, ret, args in FUNCS:
        hits = find_mangled(syms, name)
        if not hits:
            missing.append(name)
            continue
        cxx = "cxx_" + name
        argtypes = ", ".join(t for t, _ in args)
        decls.append(f'{ret} {cxx}({argtypes}) __asm__("{hits[0]}");')
    out.extend(decls)
    out.append("}  // namespace")
    out.append("")
    for name, ret, args in FUNCS:
        if name in missing:
            continue
        params = ", ".join(f"{t} {n}" for t, n in args)
        calls = ", ".join(n for _, n in args)
        out.append(f'extern "C" {ret} simdutf__{name}({params}) {{')
        out.append(f"  return cxx_{name}({calls});")
        out.append("}")
        out.append("")
    with open(sys.argv[2], "w") as f:
        f.write("\n".join(out) + "\n")
    print("wrote", sys.argv[2])
    if missing:
        print("找不到 C++ 实现的名字:", ", ".join(missing))
        sys.exit(1)
    print("全部 %d 个函数都找到对应 C++ 实现" % len(FUNCS))


if __name__ == "__main__":
    main()
