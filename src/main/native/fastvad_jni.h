#pragma once
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initModel
  (JNIEnv *, jclass, jstring);

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initRingBuffer
  (JNIEnv *, jclass, jint);

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initWebRtc
  (JNIEnv *, jclass);

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_pushFrame
  (JNIEnv *, jclass, jlong, jlong, jfloatArray);

JNIEXPORT jfloat JNICALL Java_fastvad_FastVADNative_runVad
  (JNIEnv *, jclass, jlong, jlong);

JNIEXPORT jint JNICALL Java_fastvad_FastVADNative_runWebRtc
  (JNIEnv *, jclass, jlong, jshortArray);

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyModel
  (JNIEnv *, jclass, jlong);

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyRingBuffer
  (JNIEnv *, jclass, jlong);

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyWebRtc
  (JNIEnv *, jclass, jlong);

#ifdef __cplusplus
}
#endif