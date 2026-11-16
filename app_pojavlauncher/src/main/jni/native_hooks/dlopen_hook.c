//
// Ported from Amethyst-Android (v3_openjdk) to Morphyz Launcher.
// Interposes dlsym so that SDL's JNI_OnLoad is properly routed when SDL was
// loaded via LWJGL's own DynamicLinkLoader (raw dlopen), which never calls
// JNI_OnLoad through the JVM.
//

#include "environ/environ.h"
#include "utils.h"
#include "native_hooks.h"
#include "log.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// WARNING: Hooking dlopen and dlsym does not work for all devices, seems to be a conflict with
// the turnip loader, perhaps Dobby would fare better.
typedef void *(*dlopen_func_t)(const char *, int);
typedef void *(*dlsym_func_t)(void *, const char *);
typedef jint (*JNI_OnLoad_t)(JavaVM *vm, void *reserved);

typedef struct {
    const char *library_name;
    const char *library_rename;
    void *handle;
    JNI_OnLoad_t jni_onload;
} LibraryRedirect;

static LibraryRedirect libRedirectInfo[] = {
        { "libSDL3.so", "libSDL3.so" },
        { "libSDL2.so", "libSDL2.so" },
        { NULL, NULL } // Null termination
};

__attribute__((constructor)) static void init() {
    // We can't hook dlopen so we make do with this.
    libRedirectInfo[0].handle = dlopen("libSDL3.so", RTLD_LOCAL | RTLD_LAZY);
    libRedirectInfo[1].handle = dlopen("libSDL2.so", RTLD_LOCAL | RTLD_LAZY);
}

// Loose matching of lib file name to hard coded list
static LibraryRedirect* get_library_redirect_if_needed(const char *filename) {
    if (filename == NULL)
        return NULL;
    const char *basename = strrchr(filename, '/');
    basename = basename ? basename + 1 : filename;
    for (size_t i = 0; libRedirectInfo[i].library_name != NULL; ++i) {
        if (strstr(basename, libRedirectInfo[i].library_name) != NULL) {
            return &libRedirectInfo[i];
        }
    }
    return NULL;
}

void *custom_dlopen(const char *filename, int flags) {
    LibraryRedirect *redirect = get_library_redirect_if_needed(filename);
    const char *target_filename = filename;
    if (redirect != NULL) {
        target_filename = redirect->library_rename;
        LOGI("Redirecting dlopen: %s -> %s", filename, target_filename);
    }

    void *result = BYTEHOOK_CALL_PREV(
            custom_dlopen,
            dlopen_func_t,
            target_filename,
            flags);

    if (redirect != NULL)
        redirect->handle = result;

    BYTEHOOK_POP_STACK();
    return result;
}

static LibraryRedirect *get_redirect_by_handle(void *handle) {
    if (handle == NULL)
        return NULL;

    for (size_t i = 0; libRedirectInfo[i].library_name != NULL; ++i) {
        if (libRedirectInfo[i].handle == handle)
            return &libRedirectInfo[i];
    }

    return NULL;
}

static jint rerouted_jni_onload(
        LibraryRedirect *redirect,
        JavaVM *vm,
        void *reserved) {

    if (redirect == NULL) {
        return JNI_ERR;
    }

    if (pojav_environ != NULL &&
            pojav_environ->dalvikJavaVMPtr == vm &&
            redirect->jni_onload != NULL) {
        return ((JNI_OnLoad_t) redirect->jni_onload)(vm, reserved);
    }

    return JNI_VERSION_1_4;
}

#define DEFINE_JNI_WRAPPER(id)                                              \
    static jint jni_onload_##id(                                            \
            JavaVM *vm,                                                     \
            void *reserved) {                                               \
        return rerouted_jni_onload(&libRedirectInfo[id], vm, reserved);     \
    }

// This is a bit magicky yeah
DEFINE_JNI_WRAPPER(0)
DEFINE_JNI_WRAPPER(1)

static JNI_OnLoad_t get_jni_onload_wrapper(
        LibraryRedirect *redirect) {
    if (redirect == &libRedirectInfo[0]) {
        return jni_onload_0;
    }

    if (redirect == &libRedirectInfo[1]) {
        return jni_onload_1;
    }

    return NULL;
}

// MC 26.x resolves SDL_Init/SDL_InitSubSystem through LWJGL's apiGetFunctionAddress
// (a raw dlsym'd function pointer), which completely bypasses the SDL_InitSubSystem
// PLT bytehook. Return a trampoline instead, guaranteeing SDL_SetMainReady() ran
// before the real init - otherwise SDL refuses to start with
// "Application didn't initialize properly, did you include SDL_main.h...".
typedef bool (*sdl_init_func_t)(uint32_t);
static void *g_sdl3_handle = NULL;
static sdl_init_func_t g_real_sdl_init = NULL;
static sdl_init_func_t g_real_sdl_init_subsystem = NULL;

static void force_main_ready(void *handle) {
    if (handle == NULL) {
        return;
    }
    void (*SDL_SetMainReady_p)(void) = (void (*)(void))dlsym(handle, "SDL_SetMainReady");
    if (SDL_SetMainReady_p != NULL) {
        SDL_SetMainReady_p();
        LOGI("Morphyz Launcher: forced SDL_SetMainReady() via dlsym trampoline");
    }
}

static bool hooked_sdl_init(uint32_t flags) {
    force_main_ready(g_sdl3_handle);
    return g_real_sdl_init(flags);
}

static bool hooked_sdl_init_subsystem(uint32_t flags) {
    force_main_ready(g_sdl3_handle);
    return g_real_sdl_init_subsystem(flags);
}

void *custom_dlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(
            custom_dlsym,
            dlsym_func_t,
            handle,
            symbol);
    LibraryRedirect *redirect = get_redirect_by_handle(handle);
    if ((redirect != NULL) && strcmp(symbol, "JNI_OnLoad") == 0) {
        if (strstr(redirect->library_name, "libSDL3")) {
            redirect->jni_onload = (JNI_OnLoad_t) result;
            // This outputs in the minecraft logs
            LOGI("Morphyz Launcher: Intercepted dlsym on SDL3 JNI_OnLoad: %p", result);
            BYTEHOOK_POP_STACK();
            return get_jni_onload_wrapper(redirect);
        }
    }
    if (redirect != NULL && result != NULL &&
            strstr(redirect->library_name, "libSDL3") != NULL) {
        if (strcmp(symbol, "SDL_Init") == 0) {
            g_sdl3_handle = handle;
            g_real_sdl_init = (sdl_init_func_t) result;
            LOGI("Morphyz Launcher: Intercepted dlsym on SDL3 SDL_Init: %p", result);
            BYTEHOOK_POP_STACK();
            return (void *) hooked_sdl_init;
        }
        if (strcmp(symbol, "SDL_InitSubSystem") == 0) {
            g_sdl3_handle = handle;
            g_real_sdl_init_subsystem = (sdl_init_func_t) result;
            LOGI("Morphyz Launcher: Intercepted dlsym on SDL3 SDL_InitSubSystem: %p", result);
            BYTEHOOK_POP_STACK();
            return (void *) hooked_sdl_init_subsystem;
        }
    }
    BYTEHOOK_POP_STACK();
    return result;
}

void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    // FIXME: Hooking dlopen causes a crash with Turnip loader, so let's stop doing that entirely
    // Maybe using android_dlopen_ext would help but I can't be bothered to do that yet
    bytehook_stub_t stub_dlopen = (void *)(uintptr_t)0xD15AB1ED;
//            bytehook_hook_all_p(NULL, "dlopen", &custom_dlopen, NULL, NULL);
    bytehook_stub_t stub_dlsym =
            bytehook_hook_all_p(NULL, "dlsym", &custom_dlsym, NULL, NULL);
    LOGI("Successfully initialized dlopen hooks, stub: %p %p", stub_dlopen, stub_dlsym);
}
