/* Exercises the real launcher failure path without opening a modal AppKit alert. */
#include <assert.h>
#include "launch.c"

static char *displayed;
void psd2live_show_error(const char *message) {
	free(displayed);
	displayed = strdup(message);
}

int main(void) {
	char directory[] = "/tmp/psd2live-launch-test.XXXXXX";
	assert(mkdtemp(directory) != NULL);
	assert(chdir(directory) == 0);
	const char *name = "./runtime';touch INJECTED;'.dylib";
	FILE *file = fopen(name, "w");
	assert(file != NULL);
	fputs("not a shared library", file);
	fclose(file);
	assert(dlopen(name, RTLD_NOW | RTLD_GLOBAL) == NULL);
	report_dlerror("fallback");
	assert(displayed != NULL && strstr(displayed, "runtime") != NULL);
	assert(dlerror() == NULL);
	assert(access("INJECTED", F_OK) != 0);
	report_dlerror("fallback");
	assert(strcmp(displayed, "fallback") == 0);
	note_error(NULL);
	assert(strcmp(displayed, "Could not start PSD2Live.") == 0);
	free(displayed);
	unlink(name);
	assert(chdir("/") == 0);
	assert(rmdir(directory) == 0);
	return 0;
}
