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
#include <stdio.h>
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

// MC 26.x GlBackend.loadLibrary() does:
//   SDL_GL_LoadLibrary(<LWJGL's GL lib>);
//   if (SDL_GL_GetProcAddress("glGetError") != LWJGL's glGetError)
//       throw new BackendCreationException("glGetError mismatch");
// On Android SDL resolves the symbol through eglGetProcAddress(), which returns
// the *vendor driver's* pointer, while LWJGL binds the launcher's renderer
// (gl4es/MobileGlues/...). The pointers differ -> OpenGL backend creation fails
// -> MC falls back to Vulkan -> Adreno 710 lacks Vulkan 1.2 -> exit code 6.
// Fix: answer SDL_GL_GetProcAddress from the SAME library LWJGL binds
// (MORPHYZ_GL_LIB, exported by JREUtils), falling back to the real function.
typedef void *(*SDL_GL_GetProcAddress_Func)(const char *);
static SDL_GL_GetProcAddress_Func g_real_gl_getprocaddress = NULL;
static void *g_renderer_gl_handle = NULL;
static bool g_renderer_gl_resolved = false;

static void *get_renderer_gl_handle(void) {
    if (g_renderer_gl_resolved) {
        return g_renderer_gl_handle;
    }
    g_renderer_gl_resolved = true;

    const char *libName = getenv("MORPHYZ_GL_LIB");
    if (libName == NULL || libName[0] == '\0') {
        LOGI("Morphyz Launcher: MORPHYZ_GL_LIB not set, SDL_GL_GetProcAddress fix inactive");
        return NULL;
    }
    // The renderer was already dlopen()'d by the launcher (loadGraphicsLibrary),
    // so RTLD_NOLOAD just looks up the existing handle.
    void *handle = dlopen(libName, RTLD_NOLOAD | RTLD_LAZY);
    if (handle == NULL) {
        const char *nativeDir = getenv("POJAV_NATIVEDIR");
        if (nativeDir != NULL) {
            char path[512];
            snprintf(path, sizeof(path), "%s/%s", nativeDir, libName);
            handle = dlopen(path, RTLD_NOLOAD | RTLD_LAZY);
        }
    }
    if (handle == NULL) {
        LOGI("Morphyz Launcher: renderer lib %s not found for SDL_GL_GetProcAddress fix", libName);
    } else {
        g_renderer_gl_handle = handle;
        LOGI("Morphyz Launcher: SDL_GL_GetProcAddress will answer from %s (%p)", libName, handle);
    }
    return g_renderer_gl_handle;
}

static void *hooked_sdl_gl_getprocaddress(const char *name) {
    if (name != NULL) {
        void *renderer = get_renderer_gl_handle();
        if (renderer != NULL) {
            void *proc = dlsym(renderer, name);
            if (proc != NULL) {
                return proc;
            }
        }
    }
    return g_real_gl_getprocaddress != NULL ? g_real_gl_getprocaddress(name) : NULL;
}

void *custom_dlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(
            custom_dlsym,
            dlsym_func_t,
            handle,
            symbol);
    LibraryRedirect *redirect = get_redirect_by_handle(handle);
    if (redirect != NULL && result != NULL &&
            strstr(redirect->library_name, "libSDL3") != NULL &&
            strcmp(symbol, "SDL_GL_GetProcAddress") == 0) {
        g_real_gl_getprocaddress = (SDL_GL_GetProcAddress_Func) result;
        LOGI("Morphyz Launcher: Intercepted dlsym on SDL3 SDL_GL_GetProcAddress: %p", result);
        BYTEHOOK_POP_STACK();
        return (void *) hooked_sdl_gl_getprocaddress;
    }
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
