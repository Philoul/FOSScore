// Minimal hand-written JNI bridge to vrv::Toolkit (no SWIG needed).
#include <jni.h>
#include <string>

#include "toolkit.h"

namespace {

vrv::Toolkit *tk(jlong handle) { return reinterpret_cast<vrv::Toolkit *>(handle); }

std::string str(JNIEnv *env, jstring s)
{
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// NewStringUTF expects modified UTF-8; going through a byte array keeps any character intact.
jstring jstr(JNIEnv *env, const std::string &s)
{
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto out = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return out;
}

} // namespace

#define FN(name) Java_org_fosscore_spike_musicxml_Verovio_##name

extern "C" {

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *env, jclass, jstring resourcePath)
{
    auto *toolkit = new vrv::Toolkit(false);
    toolkit->SetResourcePath(str(env, resourcePath));
    return reinterpret_cast<jlong>(toolkit);
}

JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv *, jclass, jlong h) { delete tk(h); }

JNIEXPORT jstring JNICALL FN(nativeVersion)(JNIEnv *env, jclass, jlong h) { return jstr(env, tk(h)->GetVersion()); }

JNIEXPORT jboolean JNICALL FN(nativeSetOptions)(JNIEnv *env, jclass, jlong h, jstring json)
{
    return tk(h)->SetOptions(str(env, json));
}

JNIEXPORT jboolean JNICALL FN(nativeLoadData)(JNIEnv *env, jclass, jlong h, jstring data)
{
    return tk(h)->LoadData(str(env, data));
}

JNIEXPORT jboolean JNICALL FN(nativeLoadZip)(JNIEnv *env, jclass, jlong h, jbyteArray data)
{
    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    bool ok = tk(h)->LoadZipDataBuffer(reinterpret_cast<const unsigned char *>(bytes), length);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return ok;
}

JNIEXPORT void JNICALL FN(nativeRedoLayout)(JNIEnv *env, jclass, jlong h, jstring json)
{
    tk(h)->RedoLayout(str(env, json));
}

JNIEXPORT jint JNICALL FN(nativePageCount)(JNIEnv *, jclass, jlong h) { return tk(h)->GetPageCount(); }

JNIEXPORT jstring JNICALL FN(nativeRenderSvg)(JNIEnv *env, jclass, jlong h, jint page)
{
    return jstr(env, tk(h)->RenderToSVG(page, true));
}

JNIEXPORT jstring JNICALL FN(nativeRenderMidi)(JNIEnv *env, jclass, jlong h) { return jstr(env, tk(h)->RenderToMIDI()); }

JNIEXPORT jstring JNICALL FN(nativeRenderTimemap)(JNIEnv *env, jclass, jlong h)
{
    return jstr(env, tk(h)->RenderToTimemap());
}

JNIEXPORT jstring JNICALL FN(nativeLog)(JNIEnv *env, jclass, jlong h) { return jstr(env, tk(h)->GetLog()); }

} // extern "C"
