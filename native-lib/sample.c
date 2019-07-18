#include <stdint.h>

#if defined(_WIN64)
// NOTE: Doesnt matter what calling convention you specify for x64
#define NATIVE_API(x) extern _declspec(dllexport) x __fastcall
#elif defined(_WIN32)
// NOTE: thiscall, cdecl and stdcall are automatically supported by .NET PInvoke, but not fastcall
#define NATIVE_API(x) extern _declspec(dllexport) x __cdecl
#else
#define NATIVE_API(x) extern x __attribute__ ((visibility("default")))
#endif

#if defined(_WIN32)

#ifndef WINAPI
#define WINAPI __stdcall
#endif

typedef int BOOL;
typedef uint32_t DWORD;

//
// DLL Entry Point
//
extern _declspec(dllexport) BOOL WINAPI DllMain(void *instance, DWORD reason, void *reserved)
{
    return 1;
}

#endif /* #if defined(_WIN32) || defined(_WIN64) */

NATIVE_API(double) avg(int32_t a, int32_t b)
{
    return (a + b) * 0.5;
}

NATIVE_API(int) ptrSize()
{
    return sizeof(intptr_t);
}

// JNI sample:

// You can use javah tool to determine expected function names for your JNI imports
// 00024 is the hex code for '$' which is a part of inner class name that contains native function avg()
// If you don't need access to Java environment, you don't really need to #include <jni.h>
NATIVE_API(double) Java_deltix_NativeUtilsSample_00024Imports_avg(void *env, void *obj, int32_t a, int32_t b)
{
    return avg(a, b);
}
