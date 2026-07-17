/*
 * one35uinputd — uinput relay daemon for MagicX One35 (MT6768)
 *
 * Grabs /dev/input/event1 exclusively. Creates three virtual devices: a
 * gamepad, a mouse, and a keyboard (for system keys). QMK-style 5-layer
 * system with LT (layer-tap) and TG (toggle) actions. Each layer has
 * independent landscape and portrait
 * layouts; inotify detects system rotation and switches between them.
 * Layers are opaque — an unbound key in the active layer is blocked.
 *
 * Signals:
 *   SIGHUP  — reload config from file
 *   SIGUSR2 — re-sync orientation from system settings
 *   SIGTERM/SIGINT — clean shutdown
 *
 * Usage: one35uinputd <data_dir>
 *
 * Build:
 *   aarch64-linux-android21-clang -O2 -static -o one35uinputd one35uinputd.c cJSON.c
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>
#include <poll.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include "cJSON.h"

/* ── paths ───────────────────────────────────────────────────────────────── */
#define EVENT_DEV    "/dev/input/event1"
#define UINPUT_PATH  "/dev/uinput"
#ifndef KEY_APPSWITCH
#define KEY_APPSWITCH 0x1c3  /* Generic.kl → APP_SWITCH on older Android */
#endif
#ifndef KEY_APPSELECT
#define KEY_APPSELECT 0x244  /* Generic.kl → APP_SWITCH on newer Android */
#endif

#define SETTINGS_DIR "/data/system/users/0"
#define SETTINGS_XML SETTINGS_DIR "/settings_system.xml"

static char g_pid_path[256];
static char g_state_path[256];
static char g_config_path[256];
static char g_log_path[256];

#define LOG_MAX_BYTES  (512 * 1024)
#define LOG_KEEP_BYTES (256 * 1024)

/* ── virtual device identity ─────────────────────────────────────────────── */
#define VPAD_VENDOR    0x045e
#define VPAD_PRODUCT   0x02fd
#define VPAD_VERSION   0x0001
#define VPAD_NAME      "One35 Virtual Gamepad"
#define VMOUSE_VENDOR  0x045e
#define VMOUSE_PRODUCT 0x0001
#define VMOUSE_VERSION 0x0001
#define VMOUSE_NAME    "One35 Virtual Mouse"
#define VKBD_NAME      "One35 Virtual Keyboard"

/* ── timing defaults ─────────────────────────────────────────────────────── */
#define DEFAULT_LT_HOLD_MS  390
#define DEFAULT_HAPTICS_MS  0
/* Cap how long an emitted KEY_BACK is held down. The OS turns a long BACK hold into HOME;
 * auto-releasing BACK before that threshold prevents it. 0 disables (pass-through). */
#define DEFAULT_BACK_HOLD_CAP_MS 300
#define MOUSE_HZ              100
#define MOUSE_POLL_MS         (1000 / MOUSE_HZ)

/* ── axis limits ─────────────────────────────────────────────────────────── */
#define AXIS_MIN  -32767
#define AXIS_MAX   32767
#define AXIS_FLAT  4096
#define AXIS_FUZZ  16

/* ── layer / bind limits ─────────────────────────────────────────────────── */
#define MAX_LAYERS 5
#define MAX_BINDS  64

/* ── hardware quirk ──────────────────────────────────────────────────────── */
/* Left dpad ABS_HAT0X/Y centers at 480/320 instead of the standard 0. */
#define HAT_X_CENTER      480
#define HAT_Y_CENTER      320
#define JOY_DPAD_THRESH   (AXIS_MAX / 2)

/* ── action types ────────────────────────────────────────────────────────── */
typedef enum {
    ACT_NONE = 0,
    ACT_GAMEPAD_KEY,
    ACT_ANDROID_KEY,
    ACT_GAMEPAD_AXIS,
    ACT_MOUSE_BTN,
    ACT_MOUSE_SCROLL,
    ACT_LAYER_LT,
    ACT_LAYER_TG,
    ACT_ORIENT_TG,
} act_type_t;

typedef struct {
    act_type_t type;
    int        code;
    int        value;
} action_t;

typedef struct {
    int      src_code;
    action_t tap;
    action_t hold;
} bind_t;

/* ── directional types ───────────────────────────────────────────────────── */
typedef enum { DIR_UP = 0, DIR_DOWN, DIR_LEFT, DIR_RIGHT } dir_t;

typedef enum {
    ROT_FOLLOW = 0,   /* follow layout orientation */
    ROT_LANDSCAPE,
    ROT_PORTRAIT,
} rot_override_t;

typedef enum {
    INPUT_FUNC_NONE = 0,
    INPUT_FUNC_LEFT_DPAD,    /* output as ABS_HAT0X/Y */
    INPUT_FUNC_RIGHT_DPAD,   /* output as KEY_UP/DOWN/LEFT/RIGHT */
    INPUT_FUNC_LEFT_JOY,     /* output as ABS_X/Y */
    INPUT_FUNC_RIGHT_JOY,    /* output as ABS_Z/ABS_RZ */
    INPUT_FUNC_MOUSE,        /* drives virtual mouse cursor */
    INPUT_FUNC_BUTTON,       /* each direction individually bound (dpad only) */
} input_func_t;

typedef struct {
    input_func_t   function;
    rot_override_t rotation_override;
    action_t       up, down, left, right;  /* INPUT_FUNC_BUTTON only */
} dir_config_t;

/* ── layout ──────────────────────────────────────────────────────────────── */
typedef struct {
    dir_config_t   left_dpad;
    dir_config_t   right_dpad;
    dir_config_t   left_joystick;
    bind_t         binds[MAX_BINDS];   /* controller_buttons + system_buttons merged */
    int            nbinds;
} layout_t;

typedef struct {
    layout_t landscape;
    layout_t portrait;
} layer_t;

/* ── config ──────────────────────────────────────────────────────────────── */
typedef struct {
    layer_t layers[MAX_LAYERS];
    int     nlayers;
    int     lt_hold_ms;
    int     mouse_dead_zone_pct;
    int     mouse_speed_pct;
    int     mouse_accel_pct;
    int     mouse_accel_zone_pct;
    int     haptics_ms;
    int     back_hold_cap_ms;
    int     brightness_step;
} config_t;

/* ── LT state ────────────────────────────────────────────────────────────── */
typedef enum { LT_IDLE = 0, LT_PENDING, LT_EXPIRED, LT_HELD } lt_state_t;

/* ── globals ─────────────────────────────────────────────────────────────── */
static config_t    g_cfg;
static int         g_orientation_portrait = 0;
static int         g_rotation_pending     = 0;
static int         g_toggled_layer        = 0;
static int         g_momentary_layer      = -1;

static lt_state_t      g_lt_state = LT_IDLE;
static int             g_lt_src   = -1;
static int             g_lt_layer = -1;
static action_t        g_lt_tap;
static struct timespec g_lt_down_ts;

/* KEY_BACK hold-cap state: BACK is auto-released after back_hold_cap_ms so the OS never
 * sees a hold long enough to convert it into HOME. */
static int             g_back_held = 0;
static struct timespec g_back_down_ts;

static pid_t g_pid      = 0;
static int g_fd_src     = -1;
static int g_fd_pad     = -1;
static int g_fd_mouse   = -1;
static int g_fd_kbd     = -1;
static int g_fd_inotify = -1;

static int g_joy_x = 0, g_joy_y = 0;
/* dpad→mouse: held directions and resulting velocity */
static int g_dpad_mouse_held[4] = {0};  /* indexed by dir_t */
static int g_dpad_mouse_x = 0, g_dpad_mouse_y = 0;
/* joystick→dpad: current digital state */
static int g_joy_dpad_x = 0, g_joy_dpad_y = 0;
static int g_hat_x = 0, g_hat_y = 0;
static int g_ldpad_active[4] = {0};
static struct timespec g_next_mouse_tick;

static volatile sig_atomic_t g_sig_hup  = 0;
static volatile sig_atomic_t g_sig_usr2 = 0;
static volatile sig_atomic_t g_sig_term = 0;

/* ── signal handler ──────────────────────────────────────────────────────── */
static void on_sig(int s) {
    if (s == SIGHUP)                       g_sig_hup  = 1;
    else if (s == SIGUSR2)                 g_sig_usr2 = 1;
    else if (s == SIGTERM || s == SIGINT)  g_sig_term = 1;
}

/* ── time helpers ────────────────────────────────────────────────────────── */
static long ms_since(const struct timespec *t) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec  - t->tv_sec)  * 1000L +
           (now.tv_nsec - t->tv_nsec) / 1000000L;
}

/* ── uinput helpers ──────────────────────────────────────────────────────── */
static void uinput_emit(int fd, int type, int code, int value) {
    struct input_event ev = {0};
    ev.type  = type;
    ev.code  = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

static void uinput_sync(int fd) {
    uinput_emit(fd, EV_SYN, SYN_REPORT, 0);
}

static int uinput_setup_gamepad(void) {
    int fd = open(UINPUT_PATH, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { perror("open uinput gamepad"); return -1; }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    int keys[] = {
        BTN_A, BTN_B, BTN_X, BTN_Y,
        BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
        BTN_SELECT, BTN_START, BTN_MODE,
        BTN_THUMBL, BTN_THUMBR,
        KEY_BACK,
        KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
        -1
    };
    for (int i = 0; keys[i] >= 0; i++)
        ioctl(fd, UI_SET_KEYBIT, keys[i]);

    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_Z);
    ioctl(fd, UI_SET_ABSBIT, ABS_RZ);
    ioctl(fd, UI_SET_ABSBIT, ABS_HAT0X);
    ioctl(fd, UI_SET_ABSBIT, ABS_HAT0Y);

    struct uinput_setup usetup = {0};
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor  = VPAD_VENDOR;
    usetup.id.product = VPAD_PRODUCT;
    usetup.id.version = VPAD_VERSION;
    strncpy(usetup.name, VPAD_NAME, UINPUT_MAX_NAME_SIZE - 1);

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        perror("UI_DEV_SETUP gamepad"); close(fd); return -1;
    }

    struct uinput_abs_setup abs = {0};
    int std_axes[] = { ABS_X, ABS_Y, ABS_Z, ABS_RZ, -1 };
    for (int i = 0; std_axes[i] >= 0; i++) {
        abs.code = std_axes[i];
        abs.absinfo.minimum = AXIS_MIN;
        abs.absinfo.maximum = AXIS_MAX;
        abs.absinfo.flat    = AXIS_FLAT;
        abs.absinfo.fuzz    = AXIS_FUZZ;
        ioctl(fd, UI_ABS_SETUP, &abs);
    }
    abs.code = ABS_HAT0X; abs.absinfo.minimum = -1; abs.absinfo.maximum = 1;
    abs.absinfo.flat = 0; abs.absinfo.fuzz = 0;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_HAT0Y;
    ioctl(fd, UI_ABS_SETUP, &abs);

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE gamepad"); close(fd); return -1;
    }
    return fd;
}

static int uinput_setup_mouse(void) {
    int fd = open(UINPUT_PATH, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { perror("open uinput mouse"); return -1; }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);

    struct uinput_setup usetup = {0};
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor  = VMOUSE_VENDOR;
    usetup.id.product = VMOUSE_PRODUCT;
    usetup.id.version = VMOUSE_VERSION;
    strncpy(usetup.name, VMOUSE_NAME, UINPUT_MAX_NAME_SIZE - 1);

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        perror("UI_DEV_SETUP mouse"); close(fd); return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE mouse"); close(fd); return -1;
    }
    return fd;
}

static int uinput_setup_keyboard(void) {
    int fd = open(UINPUT_PATH, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { perror("open uinput keyboard"); return -1; }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    /* all keys that android_key actions may emit.
     * 172 = KEY_HOMEPAGE (AC Home) — Generic.kl maps this to KEYCODE_HOME.
     * KEY_HOME (102) is a separate key that may not be in Generic.kl on all devices.
     * KEY_APPSWITCH (451) is the standard recents key in Generic.kl on older Android;
     * KEY_APPSELECT (580) is an alias used on newer versions — register both. */
    int keys[] = {
        KEY_BACK, 172 /* KEY_HOMEPAGE */, KEY_HOME,
        KEY_VOLUMEUP, KEY_VOLUMEDOWN, KEY_POWER,
        KEY_BRIGHTNESSUP, KEY_BRIGHTNESSDOWN,
        KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
        KEY_APPSWITCH, KEY_APPSELECT,
        -1
    };
    for (int i = 0; keys[i] >= 0; i++)
        ioctl(fd, UI_SET_KEYBIT, keys[i]);

    /* no vendor/product — Android falls back to Generic.kl which maps all
     * standard KEY_* codes including HOME, APP_SWITCH, etc. */
    struct uinput_setup usetup = {0};
    usetup.id.bustype = BUS_VIRTUAL;
    strncpy(usetup.name, VKBD_NAME, UINPUT_MAX_NAME_SIZE - 1);

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        perror("UI_DEV_SETUP keyboard"); close(fd); return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE keyboard"); close(fd); return -1;
    }
    return fd;
}

/* ── layout / bind lookup ────────────────────────────────────────────────── */
static layout_t *current_layout(void) {
    int active = (g_momentary_layer >= 0) ? g_momentary_layer : g_toggled_layer;
    if (active >= g_cfg.nlayers) active = 0;
    layer_t *ly = &g_cfg.layers[active];
    return g_orientation_portrait ? &ly->portrait : &ly->landscape;
}

static const bind_t *find_bind(int src_code) {
    layout_t *lo = current_layout();
    for (int i = 0; i < lo->nbinds; i++)
        if (lo->binds[i].src_code == src_code)
            return &lo->binds[i];
    return NULL;
}

/* ── forward declarations ────────────────────────────────────────────────── */
static void trigger_haptic(void);
static void exec_orient(int portrait);
static void adjust_brightness(int up);
static void write_state(void);

/* ── action dispatch ─────────────────────────────────────────────────────── */
static void dispatch_action(const action_t *act, int value) {
    if (!act || act->type == ACT_NONE) return;

    switch (act->type) {
    case ACT_GAMEPAD_KEY:
        uinput_emit(g_fd_pad, EV_KEY, act->code, value);
        uinput_sync(g_fd_pad);
        break;

    case ACT_ANDROID_KEY:
        /* Brightness keys: use settings-based linear stepping (avoids
         * Android's baked-in non-linear curve on MT6768 firmware). */
        if ((act->code == KEY_BRIGHTNESSUP || act->code == KEY_BRIGHTNESSDOWN)
            && value == 1 && g_cfg.brightness_step > 0) {
            adjust_brightness(act->code == KEY_BRIGHTNESSUP);
            break;
        }
        if (act->code == KEY_BACK && g_cfg.back_hold_cap_ms > 0) {
            if (value) {
                uinput_emit(g_fd_kbd, EV_KEY, KEY_BACK, 1);
                uinput_sync(g_fd_kbd);
                uinput_emit(g_fd_pad, EV_KEY, KEY_BACK, 1);
                uinput_sync(g_fd_pad);
                g_back_held = 1;
                clock_gettime(CLOCK_MONOTONIC, &g_back_down_ts);
            } else if (g_back_held) {
                uinput_emit(g_fd_kbd, EV_KEY, KEY_BACK, 0);
                uinput_sync(g_fd_kbd);
                uinput_emit(g_fd_pad, EV_KEY, KEY_BACK, 0);
                uinput_sync(g_fd_pad);
                g_back_held = 0;
            }
        } else if (act->code == KEY_BACK) {
            uinput_emit(g_fd_kbd, EV_KEY, KEY_BACK, value);
            uinput_sync(g_fd_kbd);
            uinput_emit(g_fd_pad, EV_KEY, KEY_BACK, value);
            uinput_sync(g_fd_pad);
        } else {
            uinput_emit(g_fd_kbd, EV_KEY, act->code, value);
            uinput_sync(g_fd_kbd);
        }
        break;

    case ACT_GAMEPAD_AXIS:
        uinput_emit(g_fd_pad, EV_ABS, act->code, value ? act->value : 0);
        uinput_sync(g_fd_pad);
        break;

    case ACT_MOUSE_BTN:
        uinput_emit(g_fd_mouse, EV_KEY, act->code, value);
        uinput_sync(g_fd_mouse);
        break;

    case ACT_MOUSE_SCROLL:
        if (value == 1) {
            uinput_emit(g_fd_mouse, EV_REL, REL_WHEEL, act->value ? act->value : 1);
            uinput_sync(g_fd_mouse);
        }
        break;

    case ACT_LAYER_TG:
        if (value == 1) {
            int n = act->code;
            if (n >= 0 && n < g_cfg.nlayers) {
                g_toggled_layer = (g_toggled_layer == n) ? 0 : n;
                fprintf(stderr, "one35uinputd: layer → %d\n", g_toggled_layer);
                trigger_haptic();
            }
        }
        break;

    case ACT_ORIENT_TG:
        if (value == 1) {
            g_orientation_portrait = !g_orientation_portrait;
            write_state();
            fprintf(stderr, "one35uinputd: orient → %s\n",
                    g_orientation_portrait ? "portrait" : "landscape");
            trigger_haptic();
            exec_orient(g_orientation_portrait);
        }
        break;

    case ACT_LAYER_LT:
        /* handled in handle_key_event as LT state machine — not dispatched directly */
    default:
        break;
    }
}

/* ── direction helpers ───────────────────────────────────────────────────── */
static dir_t rotate_cw(dir_t d) {
    switch (d) {
    case DIR_UP:    return DIR_RIGHT;
    case DIR_RIGHT: return DIR_DOWN;
    case DIR_DOWN:  return DIR_LEFT;
    case DIR_LEFT:  return DIR_UP;
    default:        return d;
    }
}

static int effective_portrait(rot_override_t ro) {
    if (ro == ROT_FOLLOW)   return g_orientation_portrait;
    return (ro == ROT_PORTRAIT) ? 1 : 0;
}

/* ── directional output ──────────────────────────────────────────────────── */
static void emit_hat_dir(dir_t out_dir, int value) {
    int axis = (out_dir == DIR_UP || out_dir == DIR_DOWN) ? ABS_HAT0Y : ABS_HAT0X;
    int v    = value ? ((out_dir == DIR_UP || out_dir == DIR_LEFT) ? -1 : 1) : 0;
    uinput_emit(g_fd_pad, EV_ABS, axis, v);
    uinput_sync(g_fd_pad);
}

static void emit_dpad_key(dir_t out_dir, int value) {
    static const int codes[4] = { KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT };
    uinput_emit(g_fd_pad, EV_KEY, codes[(int)out_dir], value);
    uinput_sync(g_fd_pad);
}

static void emit_joy_digital(dir_t out_dir, int right, int value) {
    int x_axis = right ? ABS_Z  : ABS_X;
    int y_axis = right ? ABS_RZ : ABS_Y;
    switch (out_dir) {
    case DIR_LEFT:  uinput_emit(g_fd_pad, EV_ABS, x_axis, value ? AXIS_MIN : 0); break;
    case DIR_RIGHT: uinput_emit(g_fd_pad, EV_ABS, x_axis, value ? AXIS_MAX : 0); break;
    case DIR_UP:    uinput_emit(g_fd_pad, EV_ABS, y_axis, value ? AXIS_MIN : 0); break;
    case DIR_DOWN:  uinput_emit(g_fd_pad, EV_ABS, y_axis, value ? AXIS_MAX : 0); break;
    }
    uinput_sync(g_fd_pad);
}

static void dispatch_dir(const dir_config_t *cfg, dir_t phys_dir, int value) {
    if (cfg->function == INPUT_FUNC_NONE) return;

    dir_t out_dir = effective_portrait(cfg->rotation_override)
                    ? rotate_cw(phys_dir) : phys_dir;

    switch (cfg->function) {
    case INPUT_FUNC_LEFT_DPAD:  emit_hat_dir(out_dir, value);        break;
    case INPUT_FUNC_RIGHT_DPAD: emit_dpad_key(out_dir, value);       break;
    case INPUT_FUNC_LEFT_JOY:   emit_joy_digital(out_dir, 0, value); break;
    case INPUT_FUNC_RIGHT_JOY:  emit_joy_digital(out_dir, 1, value); break;
    case INPUT_FUNC_BUTTON: {
        const action_t *act = NULL;
        switch (out_dir) {
        case DIR_UP:    act = &cfg->up;    break;
        case DIR_DOWN:  act = &cfg->down;  break;
        case DIR_LEFT:  act = &cfg->left;  break;
        case DIR_RIGHT: act = &cfg->right; break;
        }
        if (act) dispatch_action(act, value);
        break;
    }
    case INPUT_FUNC_MOUSE: {
        int was_active = g_dpad_mouse_x || g_dpad_mouse_y;
        g_dpad_mouse_held[out_dir] = value;
        g_dpad_mouse_x = (g_dpad_mouse_held[DIR_RIGHT] ? AXIS_MAX : 0)
                       - (g_dpad_mouse_held[DIR_LEFT]  ? AXIS_MAX : 0);
        g_dpad_mouse_y = (g_dpad_mouse_held[DIR_DOWN]  ? AXIS_MAX : 0)
                       - (g_dpad_mouse_held[DIR_UP]    ? AXIS_MAX : 0);
        if (!was_active && (g_dpad_mouse_x || g_dpad_mouse_y))
            clock_gettime(CLOCK_MONOTONIC, &g_next_mouse_tick);
        break;
    }
    default: break;
    }
}

/* ── default config ──────────────────────────────────────────────────────── */
static void load_default_config(config_t *cfg) {
    memset(cfg, 0, sizeof(*cfg));
    cfg->nlayers             = 1;
    cfg->lt_hold_ms          = DEFAULT_LT_HOLD_MS;
    cfg->mouse_dead_zone_pct = 12;
    cfg->mouse_speed_pct     = 100;
    cfg->mouse_accel_pct     = 100;
    cfg->mouse_accel_zone_pct = 20;
    cfg->haptics_ms           = DEFAULT_HAPTICS_MS;
    cfg->back_hold_cap_ms     = DEFAULT_BACK_HOLD_CAP_MS;
    cfg->brightness_step      = 16;

    layer_t *l0 = &cfg->layers[0];

    /* ── layer 0 landscape ── */
    layout_t *L = &l0->landscape;
    L->left_dpad.function     = INPUT_FUNC_LEFT_DPAD;
    L->right_dpad.function    = INPUT_FUNC_RIGHT_JOY;
    L->left_joystick.function = INPUT_FUNC_LEFT_JOY;

    struct { int src; int dst; } lface[] = {
        {304, BTN_A},  {305, BTN_B},  {307, BTN_X},  {308, BTN_Y},
        {310, BTN_TL}, {311, BTN_TR}, {312, BTN_TL2}, {313, BTN_TR2},
        {314, BTN_SELECT}, {315, BTN_START},
        {317, BTN_THUMBL}, {318, BTN_THUMBR},
        {0, 0}
    };
    for (int i = 0; lface[i].src; i++) {
        bind_t *b   = &L->binds[L->nbinds++];
        b->src_code = lface[i].src;
        b->tap.type = ACT_GAMEPAD_KEY;
        b->tap.code = lface[i].dst;
    }
    {
        static const int sys[] = { KEY_BACK, KEY_VOLUMEDOWN, KEY_VOLUMEUP, KEY_POWER, -1 };
        static const int src[] = { 158, 114, 115, 116, -1 };
        for (int i = 0; sys[i] >= 0; i++) {
            bind_t *b   = &L->binds[L->nbinds++];
            b->src_code = src[i];
            b->tap.type = ACT_ANDROID_KEY;
            b->tap.code = sys[i];
        }
    }

    /* ── layer 0 portrait ── */
    layout_t *P = &l0->portrait;
    P->left_dpad.function     = INPUT_FUNC_NONE;
    P->right_dpad.function    = INPUT_FUNC_RIGHT_DPAD;
    P->left_joystick.function = INPUT_FUNC_NONE;

    struct { int src; int dst; } pface[] = {
        {307, BTN_A}, {304, BTN_B}, {305, BTN_Y}, {308, BTN_X},
        {309, BTN_TL}, {306, BTN_TR},
        {314, BTN_SELECT}, {315, BTN_START},
        {0, 0}
    };
    for (int i = 0; pface[i].src; i++) {
        bind_t *b   = &P->binds[P->nbinds++];
        b->src_code = pface[i].src;
        b->tap.type = ACT_GAMEPAD_KEY;
        b->tap.code = pface[i].dst;
    }
    int punbound[] = {310, 311, 312, 313, 317, 318, 0};
    for (int i = 0; punbound[i]; i++) {
        bind_t *b   = &P->binds[P->nbinds++];
        b->src_code = punbound[i];
        b->tap.type = ACT_NONE;
    }
    {
        static const int sys[] = { KEY_BACK, KEY_VOLUMEDOWN, KEY_VOLUMEUP, KEY_POWER, -1 };
        static const int src[] = { 158, 114, 115, 116, -1 };
        for (int i = 0; sys[i] >= 0; i++) {
            bind_t *b   = &P->binds[P->nbinds++];
            b->src_code = src[i];
            b->tap.type = ACT_ANDROID_KEY;
            b->tap.code = sys[i];
        }
    }
}

/* ── config file parser ──────────────────────────────────────────────────── */
static act_type_t parse_act_type(const char *s) {
    if (!s)                          return ACT_NONE;
    if (!strcmp(s, "gamepad_key"))   return ACT_GAMEPAD_KEY;
    if (!strcmp(s, "android_key"))   return ACT_ANDROID_KEY;
    if (!strcmp(s, "gamepad_axis"))  return ACT_GAMEPAD_AXIS;
    if (!strcmp(s, "mouse_btn"))     return ACT_MOUSE_BTN;
    if (!strcmp(s, "mouse_scroll"))  return ACT_MOUSE_SCROLL;
    if (!strcmp(s, "layer_lt"))      return ACT_LAYER_LT;
    if (!strcmp(s, "layer_tg"))      return ACT_LAYER_TG;
    if (!strcmp(s, "orient_tg"))     return ACT_ORIENT_TG;
    return ACT_NONE;
}

static input_func_t parse_input_func(const char *s) {
    if (!s)                           return INPUT_FUNC_NONE;
    if (!strcmp(s, "left_dpad"))      return INPUT_FUNC_LEFT_DPAD;
    if (!strcmp(s, "dpad"))           return INPUT_FUNC_RIGHT_DPAD;
    if (!strcmp(s, "left_joystick"))  return INPUT_FUNC_LEFT_JOY;
    if (!strcmp(s, "right_joystick")) return INPUT_FUNC_RIGHT_JOY;
    if (!strcmp(s, "mouse"))          return INPUT_FUNC_MOUSE;
    if (!strcmp(s, "button_mode"))    return INPUT_FUNC_BUTTON;
    return INPUT_FUNC_NONE;
}

static rot_override_t parse_rot_override(const cJSON *j) {
    const char *s = cJSON_GetStringValue(j);
    if (!s)                         return ROT_FOLLOW;
    if (!strcmp(s, "landscape"))    return ROT_LANDSCAPE;
    if (!strcmp(s, "portrait"))     return ROT_PORTRAIT;
    return ROT_FOLLOW;
}

static void parse_action(const cJSON *j, action_t *act) {
    memset(act, 0, sizeof(*act));
    if (!cJSON_IsObject(j)) return;
    act->type = parse_act_type(cJSON_GetStringValue(cJSON_GetObjectItem(j, "type")));
    cJSON *c  = cJSON_GetObjectItem(j, "code");
    if (cJSON_IsNumber(c)) act->code  = (int)c->valuedouble;
    cJSON *v  = cJSON_GetObjectItem(j, "value");
    if (cJSON_IsNumber(v)) act->value = (int)v->valuedouble;
}

static void parse_dir_config(const cJSON *j, dir_config_t *cfg) {
    if (!cJSON_IsObject(j)) return;
    cfg->function = parse_input_func(
        cJSON_GetStringValue(cJSON_GetObjectItem(j, "function")));
    cfg->rotation_override = parse_rot_override(
        cJSON_GetObjectItem(j, "rotation_override"));
    if (cfg->function == INPUT_FUNC_BUTTON) {
        parse_action(cJSON_GetObjectItem(j, "up"),    &cfg->up);
        parse_action(cJSON_GetObjectItem(j, "down"),  &cfg->down);
        parse_action(cJSON_GetObjectItem(j, "left"),  &cfg->left);
        parse_action(cJSON_GetObjectItem(j, "right"), &cfg->right);
    }
}

static void parse_binds(const cJSON *j, layout_t *lo) {
    if (!cJSON_IsObject(j)) return;
    cJSON *binds = cJSON_GetObjectItem(j, "binds");
    if (!cJSON_IsArray(binds)) return;
    cJSON *jb;
    cJSON_ArrayForEach(jb, binds) {
        if (lo->nbinds >= MAX_BINDS) break;
        if (!cJSON_IsObject(jb)) continue;
        bind_t *b  = &lo->binds[lo->nbinds++];
        cJSON *src = cJSON_GetObjectItem(jb, "src");
        if (cJSON_IsNumber(src)) b->src_code = (int)src->valuedouble;
        parse_action(cJSON_GetObjectItem(jb, "tap"),  &b->tap);
        parse_action(cJSON_GetObjectItem(jb, "hold"), &b->hold);
    }
}

static void parse_layout(const cJSON *j, layout_t *lo) {
    if (!cJSON_IsObject(j)) return;
    parse_dir_config(cJSON_GetObjectItem(j, "left_dpad"),      &lo->left_dpad);
    parse_dir_config(cJSON_GetObjectItem(j, "right_dpad"),     &lo->right_dpad);
    parse_dir_config(cJSON_GetObjectItem(j, "left_joystick"),  &lo->left_joystick);
    parse_binds(cJSON_GetObjectItem(j, "controller_buttons"), lo);
    parse_binds(cJSON_GetObjectItem(j, "system_buttons"),     lo);
}

static void load_config(config_t *cfg) {
    FILE *f = fopen(g_config_path, "r");
    if (!f) {
        fprintf(stderr, "one35uinputd: no config file, using defaults\n");
        return;
    }

    char *buf = malloc(65537);
    if (!buf) { fclose(f); return; }
    size_t sz = fread(buf, 1, 65536, f);
    fclose(f);
    if (!sz) {
        fprintf(stderr, "one35uinputd: config file empty, using defaults\n");
        free(buf);
        return;
    }
    buf[sz] = '\0';

    cJSON *root = cJSON_Parse(buf);
    free(buf);
    if (!root) {
        fprintf(stderr, "one35uinputd: config parse error — %s\n",
                cJSON_GetErrorPtr() ? cJSON_GetErrorPtr() : "unknown");
        return;
    }

    cJSON *j;
    cJSON *global = cJSON_GetObjectItem(root, "global");
    if (cJSON_IsObject(global)) {
        j = cJSON_GetObjectItem(global, "lt_hold_ms");
        if (cJSON_IsNumber(j)) cfg->lt_hold_ms  = (int)j->valuedouble;
        j = cJSON_GetObjectItem(global, "haptics_ms");
        if (cJSON_IsNumber(j)) cfg->haptics_ms  = (int)j->valuedouble;
        j = cJSON_GetObjectItem(global, "back_hold_cap_ms");
        if (cJSON_IsNumber(j)) cfg->back_hold_cap_ms = (int)j->valuedouble;
        j = cJSON_GetObjectItem(global, "brightness_step");
        if (cJSON_IsNumber(j)) cfg->brightness_step  = (int)j->valuedouble;
        cJSON *mouse = cJSON_GetObjectItem(global, "mouse");
        if (cJSON_IsObject(mouse)) {
            j = cJSON_GetObjectItem(mouse, "dead_zone_pct");
            if (cJSON_IsNumber(j)) cfg->mouse_dead_zone_pct  = (int)j->valuedouble;
            j = cJSON_GetObjectItem(mouse, "speed_pct");
            if (cJSON_IsNumber(j)) cfg->mouse_speed_pct      = (int)j->valuedouble;
            j = cJSON_GetObjectItem(mouse, "accel_pct");
            if (cJSON_IsNumber(j)) cfg->mouse_accel_pct      = (int)j->valuedouble;
            j = cJSON_GetObjectItem(mouse, "accel_zone_pct");
            if (cJSON_IsNumber(j)) cfg->mouse_accel_zone_pct = (int)j->valuedouble;
        }
    }

    cJSON *layers = cJSON_GetObjectItem(root, "layers");
    if (cJSON_IsArray(layers)) {
        int n = 0;
        cJSON *jl;
        cJSON_ArrayForEach(jl, layers) {
            if (n >= MAX_LAYERS) break;
            if (!cJSON_IsObject(jl)) { n++; continue; }
            layer_t *ly = &cfg->layers[n];
            memset(ly, 0, sizeof(*ly));
            parse_layout(cJSON_GetObjectItem(jl, "landscape"), &ly->landscape);
            parse_layout(cJSON_GetObjectItem(jl, "portrait"),  &ly->portrait);
            n++;
        }
        if (n > 0) cfg->nlayers = n;
    }

    cJSON_Delete(root);
    fprintf(stderr, "one35uinputd: config loaded from %s\n", g_config_path);
}

/* ── state file ──────────────────────────────────────────────────────────── */
static void write_state(void) {
    FILE *f = fopen(g_state_path, "w");
    if (!f) return;
    fprintf(f,
            "{\"daemon_running\":true,"
            "\"active_layer\":%d,"
            "\"orientation\":\"%s\","
            "\"pid\":%d}\n",
            (g_momentary_layer >= 0) ? g_momentary_layer : g_toggled_layer,
            g_orientation_portrait ? "portrait" : "landscape",
            (int)g_pid);
    fclose(f);
}

/* ── mouse movement tick ─────────────────────────────────────────────────── */
static void mouse_tick(void) {
    int dz    = (int)((long)g_cfg.mouse_dead_zone_pct * AXIS_MAX / 100);
    int speed = g_cfg.mouse_speed_pct;
    int accel = g_cfg.mouse_accel_pct;
    int az    = g_cfg.mouse_accel_zone_pct;

    /* derive velocity: joystick (if in mouse mode) + any dpad mouse dirs */
    layout_t *lo = current_layout();
    int x = g_dpad_mouse_x, y = g_dpad_mouse_y;
    if (lo->left_joystick.function == INPUT_FUNC_MOUSE) {
        int portrait = effective_portrait(lo->left_joystick.rotation_override);
        x += portrait ?  g_joy_y : g_joy_x;
        y += portrait ? -g_joy_x : g_joy_y;
    }
    if (x > AXIS_MAX) x = AXIS_MAX; else if (x < AXIS_MIN) x = AXIS_MIN;
    if (y > AXIS_MAX) y = AXIS_MAX; else if (y < AXIS_MIN) y = AXIS_MIN;
    if (x > -dz && x < dz) x = 0;
    if (y > -dz && y < dz) y = 0;
    if (!x && !y) return;

    int mul_fp = 100;
    if (accel != 100) {
        int range    = AXIS_MAX - dz;
        int az_limit = (int)((long)az * range / 100);
        int s_max    = range - az_limit;
        int ax = x < 0 ? -x : x;
        int ay = y < 0 ? -y : y;
        int mag = ax > ay ? ax : ay;
        int t   = mag - dz;
        if (t > az_limit && s_max > 0)
            mul_fp = 100 + (int)((long)(t - az_limit) * (accel - 100) / s_max);
    }

    int dx = (int)((long)x * speed * mul_fp / 100 / 100 / 4096);
    int dy = (int)((long)y * speed * mul_fp / 100 / 100 / 4096);
    if (dx || dy) {
        if (dx) uinput_emit(g_fd_mouse, EV_REL, REL_X, dx);
        if (dy) uinput_emit(g_fd_mouse, EV_REL, REL_Y, dy);
        uinput_sync(g_fd_mouse);
    }
}

/* ── main event handlers ─────────────────────────────────────────────────── */
static void handle_key_event(int code, int value) {
    if (value == 2) return;

    /* HAT disambiguation: suppress KEY events from left dpad when HAT active */
    int is_dpad_key = (code == KEY_UP || code == KEY_DOWN ||
                       code == KEY_LEFT || code == KEY_RIGHT);
    int dpad_dir    = is_dpad_key
                      ? ((code == KEY_UP)   ? 0 : (code == KEY_DOWN)  ? 1 :
                         (code == KEY_LEFT) ? 2 : 3)
                      : -1;
    if (is_dpad_key) {
        int hat_active = ((code == KEY_UP || code == KEY_DOWN) ? g_hat_y : g_hat_x) != 0;
        if (value == 1) {
            g_ldpad_active[dpad_dir] = hat_active;
            if (hat_active) return;
        } else {
            if (g_ldpad_active[dpad_dir]) { g_ldpad_active[dpad_dir] = 0; return; }
        }
        /* surviving KEY events are from the right dpad */
    }

    /* LT release */
    if (g_lt_state != LT_IDLE && code == g_lt_src && value == 0) {
        if (g_lt_state == LT_PENDING) {
            dispatch_action(&g_lt_tap, 1);
            dispatch_action(&g_lt_tap, 0);
        } else if (g_lt_state == LT_HELD) {
            g_momentary_layer = -1;
            write_state();
            fprintf(stderr, "one35uinputd: momentary layer → 0\n");
        }
        /* LT_EXPIRED: held past threshold with no second key — do nothing */
        g_lt_state = LT_IDLE;
        return;
    }

    /* LT promotion: second key pressed while pending or expired → activate layer */
    if ((g_lt_state == LT_PENDING || g_lt_state == LT_EXPIRED) && code != g_lt_src && value == 1) {
        g_momentary_layer = g_lt_layer;
        g_lt_state        = LT_HELD;
        write_state();
        fprintf(stderr, "one35uinputd: momentary layer → %d\n", g_lt_layer);
        trigger_haptic();
    }

    /* right dpad keys route through right_dpad config */
    if (is_dpad_key) {
        dispatch_dir(&current_layout()->right_dpad, (dir_t)dpad_dir, value);
        return;
    }

    const bind_t *b = find_bind(code);
    if (!b) return;

    if (value == 1 && b->hold.type == ACT_LAYER_LT && g_lt_state == LT_IDLE) {
        g_lt_state = LT_PENDING;
        g_lt_src   = code;
        g_lt_layer = b->hold.code;
        g_lt_tap   = b->tap;
        clock_gettime(CLOCK_MONOTONIC, &g_lt_down_ts);
        return;
    }

    dispatch_action(&b->tap, value);
}

static void handle_abs_event(int code, int raw_value) {
    /* left joystick */
    if (code == ABS_X || code == ABS_Y) {
        int was_joy_zero = !g_joy_x && !g_joy_y;
        if (code == ABS_X) g_joy_x = raw_value;
        else               g_joy_y = raw_value;

        /* LT promotion via joystick: if the target layer uses left_joystick as
         * mouse, treat deflection past the dead zone as a second-input trigger
         * so "hold START + move stick" activates the layer without needing a
         * button press first. */
        if (g_lt_state == LT_PENDING || g_lt_state == LT_EXPIRED) {
            if (g_lt_layer >= 0 && g_lt_layer < g_cfg.nlayers) {
                layout_t *tgt = g_orientation_portrait
                    ? &g_cfg.layers[g_lt_layer].portrait
                    : &g_cfg.layers[g_lt_layer].landscape;
                if (tgt->left_joystick.function == INPUT_FUNC_MOUSE) {
                    int dz = (int)((long)g_cfg.mouse_dead_zone_pct * AXIS_MAX / 100);
                    int ax = g_joy_x < 0 ? -g_joy_x : g_joy_x;
                    int ay = g_joy_y < 0 ? -g_joy_y : g_joy_y;
                    if (ax > dz || ay > dz) {
                        g_momentary_layer = g_lt_layer;
                        g_lt_state        = LT_HELD;
                        write_state();
                        fprintf(stderr, "one35uinputd: momentary layer → %d\n", g_lt_layer);
                        trigger_haptic();
                    }
                }
            }
        }

        layout_t *lo = current_layout();
        int portrait = effective_portrait(lo->left_joystick.rotation_override);

        switch (lo->left_joystick.function) {
        case INPUT_FUNC_MOUSE:
            if (was_joy_zero && (g_joy_x || g_joy_y))
                clock_gettime(CLOCK_MONOTONIC, &g_next_mouse_tick);
            break;

        case INPUT_FUNC_LEFT_DPAD:
        case INPUT_FUNC_RIGHT_DPAD: {
            /* threshold-convert analog to digital directions;
               pass raw physical direction so dispatch_dir can apply rotation */
            int new_x = (g_joy_x >  JOY_DPAD_THRESH) ?  1 :
                        (g_joy_x < -JOY_DPAD_THRESH) ? -1 : 0;
            int new_y = (g_joy_y >  JOY_DPAD_THRESH) ?  1 :
                        (g_joy_y < -JOY_DPAD_THRESH) ? -1 : 0;
            if (new_x != g_joy_dpad_x) {
                if (g_joy_dpad_x)
                    dispatch_dir(&lo->left_joystick,
                                 g_joy_dpad_x < 0 ? DIR_LEFT : DIR_RIGHT, 0);
                if (new_x)
                    dispatch_dir(&lo->left_joystick,
                                 new_x < 0 ? DIR_LEFT : DIR_RIGHT, 1);
                g_joy_dpad_x = new_x;
            }
            if (new_y != g_joy_dpad_y) {
                if (g_joy_dpad_y)
                    dispatch_dir(&lo->left_joystick,
                                 g_joy_dpad_y < 0 ? DIR_UP : DIR_DOWN, 0);
                if (new_y)
                    dispatch_dir(&lo->left_joystick,
                                 new_y < 0 ? DIR_UP : DIR_DOWN, 1);
                g_joy_dpad_y = new_y;
            }
            break;
        }

        case INPUT_FUNC_LEFT_JOY: {
            int out_x = portrait ?  g_joy_y : g_joy_x;
            int out_y = portrait ? -g_joy_x : g_joy_y;
            uinput_emit(g_fd_pad, EV_ABS, ABS_X, out_x);
            uinput_emit(g_fd_pad, EV_ABS, ABS_Y, out_y);
            uinput_sync(g_fd_pad);
            break;
        }

        case INPUT_FUNC_RIGHT_JOY: {
            int out_x = portrait ?  g_joy_y : g_joy_x;
            int out_y = portrait ? -g_joy_x : g_joy_y;
            uinput_emit(g_fd_pad, EV_ABS, ABS_Z,  out_x);
            uinput_emit(g_fd_pad, EV_ABS, ABS_RZ, out_y);
            uinput_sync(g_fd_pad);
            break;
        }

        default: /* NONE — suppress */
            break;
        }
        return;
    }

    /* left dpad HAT */
    if (code == ABS_HAT0X || code == ABS_HAT0Y) {
        int center = (code == ABS_HAT0X) ? HAT_X_CENTER : HAT_Y_CENTER;
        int norm   = (raw_value > center) ? 1 : (raw_value < center) ? -1 : 0;
        int *ps    = (code == ABS_HAT0X) ? &g_hat_x : &g_hat_y;
        int  prev  = *ps;
        *ps = norm;
        if (prev == norm) return;

        layout_t *lo   = current_layout();
        dir_t dir_neg  = (code == ABS_HAT0Y) ? DIR_UP   : DIR_LEFT;
        dir_t dir_pos  = (code == ABS_HAT0Y) ? DIR_DOWN  : DIR_RIGHT;

        if (prev != 0)
            dispatch_dir(&lo->left_dpad, prev < 0 ? dir_neg : dir_pos, 0);
        if (norm != 0)
            dispatch_dir(&lo->left_dpad, norm < 0 ? dir_neg : dir_pos, 1);
        return;
    }

    /* pass through any other ABS events (triggers etc.) */
    uinput_emit(g_fd_pad, EV_ABS, code, raw_value);
    uinput_sync(g_fd_pad);
}

/* ── rotation detection ──────────────────────────────────────────────────── */
static void check_rotation(void) {
    /* settings_system.xml is Android Binary XML (ABX) — contains null bytes so
     * strstr is useless. Read the full file, use memmem to find the name, then
     * scan the next 24 bytes for the first ASCII digit which is the value. */
    struct stat st;
    if (stat(SETTINGS_XML, &st) < 0 || st.st_size <= 0) {
        g_rotation_pending = 1; return;
    }
    char *buf = malloc(st.st_size);
    if (!buf) { g_rotation_pending = 1; return; }

    FILE *f = fopen(SETTINGS_XML, "rb");
    if (!f) { free(buf); g_rotation_pending = 1; return; }
    size_t n = fread(buf, 1, st.st_size, f);
    fclose(f);
    if (!n) { free(buf); g_rotation_pending = 1; return; }

    int rot = 0;
    const char *p = memmem(buf, n, "user_rotation", 13);
    if (p) {
        const char *end   = p + 13;
        const char *limit = end + 24;
        if (limit > buf + n) limit = buf + n;
        for (const char *q = end; q < limit; q++) {
            if ((unsigned char)*q >= '0' && (unsigned char)*q <= '9') {
                rot = (unsigned char)*q - '0';
                break;
            }
        }
    }
    free(buf);
    g_rotation_pending = 0;
    int portrait = (rot == 3) ? 1 : 0;
    if (portrait != g_orientation_portrait) {
        g_orientation_portrait = portrait;
        write_state();
        fprintf(stderr, "one35uinputd: rotation → %s\n",
                portrait ? "portrait" : "landscape");
    }
}

static void drain_inotify(void) {
    char buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
    ssize_t n;
    int relevant = 0;
    while ((n = read(g_fd_inotify, buf, sizeof(buf))) > 0) {
        const char *p = buf;
        while (p + (ssize_t)sizeof(struct inotify_event) <= buf + n) {
            const struct inotify_event *ev = (const struct inotify_event *)p;
            if (ev->len > 0 && strcmp(ev->name, "settings_system.xml") == 0)
                relevant = 1;
            p += sizeof(struct inotify_event) + ev->len;
        }
    }
    if (relevant) check_rotation();
}

/* ── log rotation ────────────────────────────────────────────────────────── */
static void rotate_log_if_needed(void) {
    static unsigned iter = 0;
    if (++iter < 2000) return;
    iter = 0;

    struct stat st;
    if (stat(g_log_path, &st) < 0 || st.st_size <= LOG_MAX_BYTES) return;

    FILE *f = fopen(g_log_path, "r");
    if (!f) return;
    char *buf = malloc(LOG_KEEP_BYTES);
    if (!buf) { fclose(f); return; }
    fseek(f, -LOG_KEEP_BYTES, SEEK_END);
    size_t n = fread(buf, 1, LOG_KEEP_BYTES, f);
    fclose(f);

    char tmp[288];
    snprintf(tmp, sizeof(tmp), "%s.tmp", g_log_path);
    FILE *t = fopen(tmp, "w");
    if (!t) { free(buf); return; }
    fwrite(buf, 1, n, t);
    fclose(t);
    free(buf);
    rename(tmp, g_log_path);

    int fd = open(g_log_path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
    if (fd >= 0) { dup2(fd, STDERR_FILENO); close(fd); }
    fprintf(stderr, "one35uinputd: log rotated\n");
}

/* ── haptics ─────────────────────────────────────────────────────────────── */
typedef enum { VIB_NONE = 0, VIB_TIMED, VIB_LEDS } vib_type_t;
static vib_type_t g_vib_type = VIB_NONE;
static char       g_vib_path[256];

static void probe_vibrator(void) {
    /* timed_output: write ms to enable */
    if (access("/sys/class/timed_output/vibrator/enable", W_OK) == 0) {
        g_vib_type = VIB_TIMED;
        strncpy(g_vib_path, "/sys/class/timed_output/vibrator/enable",
                sizeof(g_vib_path) - 1);
        return;
    }
    /* leds class: write ms to duration, then 1 to activate */
    if (access("/sys/class/leds/vibrator/duration", W_OK) == 0) {
        g_vib_type = VIB_LEDS;
        strncpy(g_vib_path, "/sys/class/leds/vibrator",
                sizeof(g_vib_path) - 1);
        return;
    }
    fprintf(stderr, "one35uinputd: no vibrator sysfs node found\n");
}

static void trigger_haptic(void) {
    if (!g_cfg.haptics_ms || g_vib_type == VIB_NONE) return;
    char buf[16];
    int  n = snprintf(buf, sizeof(buf), "%d\n", g_cfg.haptics_ms);
    if (g_vib_type == VIB_TIMED) {
        int fd = open(g_vib_path, O_WRONLY);
        if (fd >= 0) { write(fd, buf, n); close(fd); }
    } else {
        char p[288];
        snprintf(p, sizeof(p), "%s/duration", g_vib_path);
        int fd = open(p, O_WRONLY);
        if (fd >= 0) { write(fd, buf, n); close(fd); }
        snprintf(p, sizeof(p), "%s/activate", g_vib_path);
        fd = open(p, O_WRONLY);
        if (fd >= 0) { write(fd, "1\n", 2); close(fd); }
    }
}

/* ── brightness: direct settings read/write for linear stepping ───────────── */
/* Reads screen_brightness via `settings get`, applies step, writes back.
 * Uses pipe+double-fork pattern (subprocess finishes in ~10ms). Zero ongoing
 * CPU cost — no polling, no state, no main-loop burden. */
static void adjust_brightness(int up) {
    int cur = -1;
    int pipefd[2];
    if (pipe(pipefd) == 0) {
        pid_t p = fork();
        if (p == 0) {
            close(pipefd[0]);
            dup2(pipefd[1], STDOUT_FILENO);
            close(pipefd[1]);
            execl("/system/bin/settings", "settings", "get", "system",
                  "screen_brightness", NULL);
            _exit(1);
        }
        close(pipefd[1]);
        char buf[16] = {0};
        ssize_t r = read(pipefd[0], buf, sizeof(buf) - 1);
        close(pipefd[0]);
        waitpid(p, NULL, 0);
        if (r > 0) {
            cur = (int)strtol(buf, NULL, 10);
        }
    }
    if (cur < 1 || cur > 255) return;

    int step  = g_cfg.brightness_step;
    int target = up ? cur + step : cur - step;
    if (target > 255) target = 255;
    if (target < 1)   target = 1;

    char val[16];
    snprintf(val, sizeof(val), "%d", target);
    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        if (fork() == 0) {
            execl("/system/bin/settings", "settings", "put", "system",
                  "screen_brightness", val, NULL);
            _exit(1);
        }
        _exit(0);
    }
    if (pid > 0) waitpid(pid, NULL, 0);
}

/* double-fork so grandchild is reparented to init — no zombie accumulation */
static void exec_orient(int portrait) {
    const char *val = portrait ? "3" : "0";
    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        if (fork() == 0) {
            execl("/system/bin/settings", "settings", "put", "system",
                  "user_rotation", val, NULL);
            _exit(1);
        }
        _exit(0);
    }
    if (pid > 0) waitpid(pid, NULL, 0);
}

/* ── main loop ───────────────────────────────────────────────────────────── */
int main(int argc, char **argv) {
    const char *data_dir = (argc > 1) ? argv[1] : "/data/local/tmp";
    snprintf(g_pid_path,    sizeof(g_pid_path),    "%s/one35uinputd.pid",   data_dir);
    snprintf(g_state_path,  sizeof(g_state_path),  "%s/one35uinputd.state", data_dir);
    snprintf(g_config_path, sizeof(g_config_path), "%s/one35uinputd.json",  data_dir);
    snprintf(g_log_path,    sizeof(g_log_path),    "%s/one35uinputd.log",   data_dir);

    g_pid = getpid();
    {
        FILE *pf = fopen(g_pid_path, "w");
        if (pf) { fprintf(pf, "%d\n", (int)g_pid); fclose(pf); }
    }

    struct sigaction sa = {0};
    sa.sa_handler = on_sig;
    sigaction(SIGHUP,  &sa, NULL);
    sigaction(SIGUSR2, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);

    load_default_config(&g_cfg);
    load_config(&g_cfg);

    g_fd_src = open(EVENT_DEV, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (g_fd_src < 0) { perror("open event1"); return 1; }

    if (ioctl(g_fd_src, EVIOCGRAB, 1) < 0) {
        perror("EVIOCGRAB"); close(g_fd_src); return 1;
    }

    g_fd_pad = uinput_setup_gamepad();
    if (g_fd_pad < 0) return 1;

    g_fd_mouse = uinput_setup_mouse();
    if (g_fd_mouse < 0) return 1;

    g_fd_kbd = uinput_setup_keyboard();
    if (g_fd_kbd < 0) return 1;

    g_fd_inotify = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (g_fd_inotify >= 0)
        inotify_add_watch(g_fd_inotify, SETTINGS_DIR,
                          IN_CLOSE_WRITE | IN_MOVED_TO);

    probe_vibrator();
    check_rotation();
    write_state();
    fprintf(stderr, "one35uinputd: started, pid %d\n", (int)g_pid);

    while (!g_sig_term) {
        rotate_log_if_needed();

        if (g_sig_hup) {
            g_sig_hup = 0;
            load_default_config(&g_cfg);
            load_config(&g_cfg);
            g_toggled_layer   = 0;
            g_momentary_layer = -1;
            g_lt_state        = LT_IDLE;
            g_lt_src          = -1;
            memset(g_dpad_mouse_held, 0, sizeof(g_dpad_mouse_held));
            g_dpad_mouse_x = g_dpad_mouse_y = 0;
            g_joy_dpad_x   = g_joy_dpad_y   = 0;
            if (g_back_held) {
                uinput_emit(g_fd_kbd, EV_KEY, KEY_BACK, 0);
                uinput_sync(g_fd_kbd);
                uinput_emit(g_fd_pad, EV_KEY, KEY_BACK, 0);
                uinput_sync(g_fd_pad);
                g_back_held = 0;
            }
            write_state();
            fprintf(stderr, "one35uinputd: config reloaded\n");
        }

        if (g_sig_usr2) {
            g_sig_usr2 = 0;
            check_rotation();
        } else if (g_rotation_pending) {
            check_rotation();
        }

        long lt_held = (g_lt_state != LT_IDLE) ? ms_since(&g_lt_down_ts) : 0;

        if (g_lt_state == LT_PENDING && lt_held >= g_cfg.lt_hold_ms)
            g_lt_state = LT_EXPIRED;

        int timeout_ms = -1;
        if (g_lt_state == LT_PENDING) {
            long until = g_cfg.lt_hold_ms - lt_held;
            timeout_ms = (int)(until > 0 ? until : 0);
        }

        /* Auto-release a held BACK once it reaches the cap, so the OS never sees a hold
         * long enough to convert into HOME. Wake the poll at the cap deadline. */
        if (g_back_held && g_cfg.back_hold_cap_ms > 0) {
            long back_ms = ms_since(&g_back_down_ts);
            if (back_ms >= g_cfg.back_hold_cap_ms) {
                uinput_emit(g_fd_kbd, EV_KEY, KEY_BACK, 0);
                uinput_sync(g_fd_kbd);
                g_back_held = 0;
            } else {
                long until = g_cfg.back_hold_cap_ms - back_ms;
                if (timeout_ms < 0 || until < timeout_ms) timeout_ms = (int)until;
            }
        }

        {
            layout_t *lo = current_layout();
            int mouse_active = g_dpad_mouse_x || g_dpad_mouse_y ||
                (lo->left_joystick.function == INPUT_FUNC_MOUSE &&
                 (g_joy_x || g_joy_y));
            if (mouse_active) {
                struct timespec now;
                clock_gettime(CLOCK_MONOTONIC, &now);
                long diff = (now.tv_sec  - g_next_mouse_tick.tv_sec)  * 1000L +
                            (now.tv_nsec - g_next_mouse_tick.tv_nsec) / 1000000L;
                int mti;
                if (diff >= 0) {
                    mouse_tick();
                    g_next_mouse_tick.tv_nsec += MOUSE_POLL_MS * 1000000L;
                    if (g_next_mouse_tick.tv_nsec >= 1000000000L) {
                        g_next_mouse_tick.tv_sec++;
                        g_next_mouse_tick.tv_nsec -= 1000000000L;
                    }
                    mti = MOUSE_POLL_MS;
                } else {
                    mti = (int)-diff;
                }
                if (timeout_ms < 0 || mti < timeout_ms) timeout_ms = mti;
            }
        }
        if (g_rotation_pending) {
            if (timeout_ms < 0 || timeout_ms > 500) timeout_ms = 500;
        }

        struct pollfd pfds[2] = {
            { g_fd_src,     POLLIN, 0 },
            { g_fd_inotify, POLLIN, 0 },
        };
        int nfds = (g_fd_inotify >= 0) ? 2 : 1;
        int r = poll(pfds, nfds, timeout_ms);
        if (r < 0) {
            if (errno == EINTR) continue;
            perror("poll"); break;
        }
        if (r == 0) continue;

        if (g_fd_inotify >= 0 && (pfds[1].revents & POLLIN))
            drain_inotify();

        struct input_event ev;
        while ((pfds[0].revents & POLLIN) &&
               read(g_fd_src, &ev, sizeof(ev)) == sizeof(ev)) {
            if (ev.type == EV_KEY)
                handle_key_event(ev.code, ev.value);
            else if (ev.type == EV_ABS)
                handle_abs_event(ev.code, ev.value);
        }
    }

    fprintf(stderr, "one35uinputd: shutting down\n");

    ioctl(g_fd_src, EVIOCGRAB, 0);
    close(g_fd_src);
    if (g_fd_inotify >= 0) close(g_fd_inotify);

    ioctl(g_fd_pad,   UI_DEV_DESTROY, 0);
    ioctl(g_fd_mouse, UI_DEV_DESTROY, 0);
    ioctl(g_fd_kbd,   UI_DEV_DESTROY, 0);
    close(g_fd_pad);
    close(g_fd_mouse);
    close(g_fd_kbd);

    FILE *sf = fopen(g_state_path, "w");
    if (sf) {
        fprintf(sf, "{\"daemon_running\":false,\"pid\":%d}\n", (int)g_pid);
        fclose(sf);
    }
    unlink(g_pid_path);

    return 0;
}
