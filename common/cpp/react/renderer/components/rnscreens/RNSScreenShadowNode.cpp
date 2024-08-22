#include "RNSScreenShadowNode.h"

namespace facebook {
namespace react {

extern const char RNSScreenComponentName[] = "RNSScreen";

Point RNSScreenShadowNode::getContentOriginOffset(
    bool /*includeTransform*/) const {
  auto stateData = getStateData();
  auto contentOffset = stateData.contentOffset;
  return {contentOffset.x, contentOffset.y};
}

    std::optional<std::reference_wrapper<const ShadowNode::Shared>>
    findHeaderConfigChild(
            const YogaLayoutableShadowNode &screenShadowNode)  {
        for (const ShadowNode::Shared &child : screenShadowNode.getChildren()) {
            if (std::strcmp(
                    child->getComponentName(), "RNSScreenStackHeaderConfig") == 0) {
                return {std::cref(child)};
            }
        }
        return {};
    }

    static constexpr const char *kScreenDummyLayoutHelperClass =
            "com/swmansion/rnscreens/utils/ScreenDummyLayoutHelper";

#ifdef ANDROID
        std::optional<float> findHeaderHeight(
                const int fontSize,
                const bool isTitleEmpty) {
            JNIEnv *env = facebook::jni::Environment::current();

            if (env == nullptr) {
                LOG(ERROR) << "[RNScreens] Failed to retrieve env\n";
                return {};
            }

            jclass layoutHelperClass = env->FindClass(kScreenDummyLayoutHelperClass);

            if (layoutHelperClass == nullptr) {
                LOG(ERROR) << "[RNScreens] Failed to find class with id "
                           << kScreenDummyLayoutHelperClass;
                return {};
            }

            jmethodID computeDummyLayoutID =
                    env->GetMethodID(layoutHelperClass, "computeDummyLayout", "(IZ)F");

            if (computeDummyLayoutID == nullptr) {
                LOG(ERROR)
                        << "[RNScreens] Failed to retrieve computeDummyLayout method ID";
                return {};
            }

            jmethodID getInstanceMethodID = env->GetStaticMethodID(
                    layoutHelperClass,
                    "getInstance",
                    "()Lcom/swmansion/rnscreens/utils/ScreenDummyLayoutHelper;");

            if (getInstanceMethodID == nullptr) {
                LOG(ERROR) << "[RNScreens] Failed to retrieve getInstanceMethodID";
                return {};
            }

            jobject packageInstance =
                    env->CallStaticObjectMethod(layoutHelperClass, getInstanceMethodID);

            if (packageInstance == nullptr) {
                LOG(ERROR)
                        << "[RNScreens] Failed to retrieve packageInstance or the package instance was null on JVM side";
                return {};
            }

            jfloat headerHeight = env->CallFloatMethod(
                    packageInstance, computeDummyLayoutID, fontSize, isTitleEmpty);

            return {headerHeight};
        }
#endif // ANDROID

void RNSScreenShadowNode::appendChild(const ShadowNode::Shared &child) {
    YogaLayoutableShadowNode::appendChild(child);
    const auto &stateData = getStateData();
#ifdef ANDROID
    if (stateData.frameSize.width == 0 || stateData.frameSize.height == 0) {
        // This code path should be executed only on the very first (few)
        // layout(s), when we haven't received state update from JVM side yet.
        auto headerConfigChildOpt = findHeaderConfigChild(*this);
        auto &screenShadowNode = static_cast<RNSScreenShadowNode &>(*this);

        // During creation of the shadow node children are not attached yet.
        // We also do not want to set any padding in case.
        if (headerConfigChildOpt) {
            const auto &headerConfigChild = headerConfigChildOpt->get();
            const auto &headerProps =
                    *std::static_pointer_cast<const RNSScreenStackHeaderConfigProps>(
                            headerConfigChild->getProps());

            const auto headerHeight = headerProps.hidden
                                      ? 0.f
                                      : findHeaderHeight(
                            headerProps.titleFontSize, headerProps.title.empty())
                                              .value_or(0.f);

            screenShadowNode.setPadding({0, 0, 0, headerHeight});
        }
    }
#endif // ANDROID
}

#ifdef ANDROID
RNSScreenShadowNode::StateData &RNSScreenShadowNode::getStateDataMutable() {
  // We assume that this method is called to mutate the data, so we ensure
  // we're unsealed.
  ensureUnsealed();
  return const_cast<RNSScreenShadowNode::StateData &>(getStateData());
}

#endif // ANDROID

} // namespace react
} // namespace facebook
