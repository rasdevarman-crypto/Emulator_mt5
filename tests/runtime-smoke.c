#include <windows.h>
#include <stdio.h>

int main(void) {
    SYSTEM_INFO info;
    GetSystemInfo(&info);
    printf("RUNTIME_SMOKE_OK pointer_bits=%u processor_count=%lu\n",
           (unsigned)(sizeof(void *) * 8),
           (unsigned long)info.dwNumberOfProcessors);
    fflush(stdout);
    return 0;
}
