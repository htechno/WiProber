#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>
#include "iperf.h"

__attribute__((constructor)) static void prepare_android_child(void) {
    /* Stream interval reports even for a pasted command without --forceflush. */
    if (getenv("WIPROBER_IPERF_CLIENT")) setvbuf(stdout, NULL, _IOLBF, 0);

    /* The ART process blocks SIGUSR1. iperf3 uses it to stop Android stream
       threads, including a receiver blocked in recv() at the end of a test. */
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGUSR1);
    sigprocmask(SIG_UNBLOCK, &signals, NULL);

    /* Finish the traffic even when Android kills the Activity's whole process. */
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() == 1) {
        _exit(1);
    }
}

/* Inspect the upstream parser's result, including long options and grouped short flags. */
int __real_iperf_parse_arguments(struct iperf_test *, int, char **);

static int local_filename(const char *name) {
    return name == NULL || (*name && !strchr(name, '/') && strcmp(name, ".") && strcmp(name, ".."));
}

int __wrap_iperf_parse_arguments(struct iperf_test *test, int argc, char **argv) {
    int result = __real_iperf_parse_arguments(test, argc, argv);
    if (result == 0 && getenv("WIPROBER_IPERF_CLIENT")) {
        if (test->role != 'c' || test->daemon) exit(80);
        /* -F, -I and --logfile operate in the test directory, outside project storage. */
        if (!local_filename(test->diskfile_name) || !local_filename(test->pidfile) ||
            !local_filename(test->logfile)) exit(81);
    }
    return result;
}
