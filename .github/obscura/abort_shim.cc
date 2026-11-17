// simdutf.o 是用 Chromium 的 libc++（std::__Cr 命名空间）编的，唯一需要的外部符号
// 是 __libcpp_verbose_abort。NDK 的 libc++ 只在 std::__ndk1 里提供同名函数，
// mangled 名对不上，所以这里直接补一个。
#include <cstdarg>
#include <cstdio>
#include <cstdlib>

extern "C" void cr_verbose_abort_def(const char* fmt, ...)
    __asm__("_ZNSt4__Cr22__libcpp_verbose_abortEPKcz");

extern "C" void cr_verbose_abort_def(const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vfprintf(stderr, fmt, ap);
  va_end(ap);
  abort();
}
