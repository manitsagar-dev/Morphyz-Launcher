
#include "environ/environ.h"
#include "utils.h"
#include "native_hooks.h"
#include "log.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

typedef bool (*SDL_InitSubSystem_Func)(uint32_t);

// Mark SDL's main as ready directly from native code. SDL_InitSubSystem refuses to run
// ("Application didn't initialize properly, did you include SDL_main.h...") until
// SDL_SetMainReady() has been called; the Java side (SDL.setupJNI -> checkJNIReady)
// normally does that, but if the JNI setup failed or was skipped we must not let the
// game die over it. Safe to call repeatedly.
static void force_sdl_main_ready(void) {
    void *handle = dlopen("libSDL3.so", RTLD_NOLOAD);
    if (handle == NULL) {
        handle = dlopen("libSDL2.so", RTLD_NOLOAD);
    }
    if (handle == NULL) {
        return;
    }
    void (*SDL_SetMainReady_p)(void) = (void (*)(void))dlsym(handle, "SDL_SetMainReady");
    if (SDL_SetMainReady_p != NULL) {
        SDL_SetMainReady_p();
        LOGI("Morphyz Launcher: forced SDL_SetMainReady() before SDL_InitSubSystem");
    }
}

static bool custom_SDL_InitSubSystem_Func(uint32_t flags) {
    force_sdl_main_ready();

    // Call notifyLauncher on SDL_InitSubSystem, this sets up all the JNI stuff needed by SDL.
    // Best-effort: even if the launcher-side notify is unavailable we still proceed with the
    // real init (main-ready was just forced above) instead of failing the game outright.
    JNIEnv *dvm_env = (pojav_environ == NULL || pojav_environ->dalvikJavaVMPtr == NULL)
            ? ((void *) 0)
            : get_attached_env(pojav_environ->dalvikJavaVMPtr);
    if (dvm_env == ((void *) 0)) {
        LOGI("SDL_InitSubSystem: launcher-side notify unavailable, continuing anyway");
    } else {
        // Just in case of bozo
        jint safeFlags;
        if (flags > INT32_MAX) {
            safeFlags = -1;
        } else safeFlags = (jint)flags;

        jint type = 0; // SDL
        jint action[] = {0, safeFlags}; // INIT, FLAG
        jintArray actionArray = (*dvm_env)->NewIntArray(dvm_env, 2);
        if (actionArray == NULL) {
            LOGI("SDL_InitSubSystem: failed to allocate the notification array");
        } else {
            (*dvm_env)->SetIntArrayRegion(dvm_env, actionArray, 0, 2, action);
            jboolean result = (*dvm_env)->CallStaticBooleanMethod(dvm_env, pojav_environ->bridgeClazz,
                    pojav_environ->method_notifyLauncher, type, actionArray);
            (*dvm_env)->DeleteLocalRef(dvm_env, actionArray);
            (void) result;
        }
    }

    // Call original func after doing all the needed setup
    bool r = BYTEHOOK_CALL_PREV(custom_SDL_InitSubSystem_Func, SDL_InitSubSystem_Func, flags);
    BYTEHOOK_POP_STACK();
    return r;
}

void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    bytehook_stub_t stub_SDL_InitSubSystem = bytehook_hook_all_p(NULL, "SDL_InitSubSystem", &custom_SDL_InitSubSystem_Func, NULL, NULL);
    LOGI("Successfully initialized SDL hooks, stubs: %p", stub_SDL_InitSubSystem);
}
