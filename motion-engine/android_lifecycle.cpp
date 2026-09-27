// Android-only process guard. Keep the model inference source unchanged.
// If Android kills the app process, do not leave a costly renderer running alone.
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <csignal>
#include <sys/prctl.h>
#include <unistd.h>

__attribute__((constructor)) static void follow_app_lifetime() {
    const pid_t parent = getppid();
    if (parent <= 1) _exit(125);
    if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) != 0) {
        std::fprintf(stderr, "ROSALINA_ERROR: Cannot install app-lifetime guard: %s\n", std::strerror(errno));
        _exit(125);
    }
    // Covers the race between reading the parent PID and installing the guard.
    if (getppid() != parent) _exit(125);
}
