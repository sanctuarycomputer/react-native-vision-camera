//
// Created by Marc Rousavy on 21.07.23.
//

#include "VisionCameraProxy.h"
#include <jsi/jsi.h>

#include "java-bindings/JFrameProcessor.h"
#include "java-bindings/JFrameProcessorPlugin.h"
#include "JSIJNIConversion.h"

#include <android/log.h>
#include <fbjni/fbjni.h>

#include "JSITypedArray.h"
#include "FrameProcessorPluginHostObject.h"

#include <vector>
#include <string>
#include <memory>

namespace vision {

using namespace facebook;

VisionCameraProxy::VisionCameraProxy(const jni::alias_ref<JVisionCameraProxy::javaobject>& javaProxy) {
  _javaProxy = make_global(javaProxy);
}

VisionCameraProxy::~VisionCameraProxy() {
  __android_log_write(ANDROID_LOG_INFO, TAG, "Destroying Context...");
  // Destroy ArrayBuffer cache for both the JS and the Worklet Runtime.
  auto workletContext = _javaProxy->cthis()->getWorkletContext();
  invalidateArrayBufferCache(*workletContext->getJsRuntime());
  invalidateArrayBufferCache(workletContext->getWorkletRuntime());
}

std::vector<jsi::PropNameID> VisionCameraProxy::getPropertyNames(jsi::Runtime& runtime) {
  std::vector<jsi::PropNameID> result;
  result.push_back(jsi::PropNameID::forUtf8(runtime, std::string("setFrameProcessor")));
  result.push_back(jsi::PropNameID::forUtf8(runtime, std::string("removeFrameProcessor")));
  result.push_back(jsi::PropNameID::forUtf8(runtime, std::string("getFrameProcessorPlugin")));
  result.push_back(jsi::PropNameID::forUtf8(runtime, std::string("isSkiaEnabled")));
  return result;
}

void VisionCameraProxy::setFrameProcessor(int viewTag, jsi::Runtime& runtime, const jsi::Object& object) {
  auto frameProcessorType = object.getProperty(runtime, "type").asString(runtime).utf8(runtime);
  auto worklet = std::make_shared<RNWorklet::JsiWorklet>(runtime, object.getProperty(runtime, "frameProcessor"));
  auto workletContext = _javaProxy->cthis()->getWorkletContext();

  jni::local_ref<JFrameProcessor::javaobject> frameProcessor;
  if (frameProcessorType == "frame-processor") {
    frameProcessor = JFrameProcessor::create(worklet, workletContext);
  } else if (frameProcessorType == "skia-frame-processor") {
#if VISION_CAMERA_ENABLE_SKIA
    throw std::runtime_error("system/skia-unavailable: Skia is not yet implemented on Android!");
#else
    throw std::runtime_error("system/skia-unavailable: Skia is not installed!");
#endif
  } else {
    throw std::runtime_error("Unknown FrameProcessor.type passed! Received: " + frameProcessorType);
  }

  _javaProxy->cthis()->setFrameProcessor(viewTag, make_global(frameProcessor));
}

void VisionCameraProxy::removeFrameProcessor(int viewTag) {
  _javaProxy->cthis()->removeFrameProcessor(viewTag);
}

jsi::Value VisionCameraProxy::getFrameProcessorPlugin(jsi::Runtime& runtime,
                                                      const std::string& name,
                                                      const jsi::Object& jsOptions) {
  auto options = JSIJNIConversion::convertJSIObjectToJNIMap(runtime, jsOptions);

  auto plugin = _javaProxy->cthis()->getFrameProcessorPlugin(name, options);

  auto pluginHostObject = std::make_shared<FrameProcessorPluginHostObject>(plugin);
  return jsi::Object::createFromHostObject(runtime, pluginHostObject);
}

jsi::Value VisionCameraProxy::get(jsi::Runtime& runtime, const jsi::PropNameID& propName) {
  auto name = propName.utf8(runtime);

  if (name == "isSkiaEnabled") {
#ifdef VISION_CAMERA_ENABLE_SKIA
    return jsi::Value(true);
#else
    return jsi::Value(false);
#endif
  }
  if (name == "setFrameProcessor") {
    return jsi::Function::createFromHostFunction(runtime,
                                                 jsi::PropNameID::forUtf8(runtime, "setFrameProcessor"),
                                                 1,
                                                 [this](jsi::Runtime& runtime,
                                                        const jsi::Value& thisValue,
                                                        const jsi::Value* arguments,
                                                        size_t count) -> jsi::Value {
      auto viewTag = arguments[0].asNumber();
      auto object = arguments[1].asObject(runtime);
      this->setFrameProcessor(static_cast<int>(viewTag), runtime, object);
      return jsi::Value::undefined();
    });
  }
  if (name == "removeFrameProcessor") {
    return jsi::Function::createFromHostFunction(runtime,
                                                 jsi::PropNameID::forUtf8(runtime, "removeFrameProcessor"),
                                                 1,
                                                 [this](jsi::Runtime& runtime,
                                                        const jsi::Value& thisValue,
                                                        const jsi::Value* arguments,
                                                        size_t count) -> jsi::Value {
      auto viewTag = arguments[0].asNumber();
      this->removeFrameProcessor(static_cast<int>(viewTag));
      return jsi::Value::undefined();
    });
  }
  if (name == "getFrameProcessorPlugin") {
    return jsi::Function::createFromHostFunction(runtime,
                                                 jsi::PropNameID::forUtf8(runtime, "getFrameProcessorPlugin"),
                                                 1,
                                                 [this](jsi::Runtime& runtime,
                                                        const jsi::Value& thisValue,
                                                        const jsi::Value* arguments,
                                                        size_t count) -> jsi::Value {
      if (count < 1 || !arguments[0].isString()) {
        throw jsi::JSError(runtime, "First argument needs to be a string (pluginName)!");
      }
      auto pluginName = arguments[0].asString(runtime).utf8(runtime);
      auto options = count > 1 ? arguments[1].asObject(runtime) : jsi::Object(runtime);

      return this->getFrameProcessorPlugin(runtime, pluginName, options);
    });
  }

  return jsi::Value::undefined();
}


void VisionCameraInstaller::install(jni::alias_ref<jni::JClass>,
                                    jni::alias_ref<JVisionCameraProxy::javaobject> proxy) {
  // global.VisionCameraProxy
  auto visionCameraProxy = std::make_shared<VisionCameraProxy>(proxy);
  jsi::Runtime& runtime = *proxy->cthis()->getWorkletContext()->getJsRuntime();
  runtime.global().setProperty(runtime,
                               "VisionCameraProxy",
                               jsi::Object::createFromHostObject(runtime, visionCameraProxy));
}

} // namespace vision