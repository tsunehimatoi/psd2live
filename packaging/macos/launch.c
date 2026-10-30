#include <CoreFoundation/CoreFoundation.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <mach-o/dyld.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static char log_path[1024];

static void note(const char *text) {
	fprintf(stderr, "psd2live: %s\n", text);
	if (log_path[0] == 0) return;
	FILE *file = fopen(log_path, "a");
	if (file == NULL) return;
	fprintf(file, "%s\n", text);
	fclose(file);
}

static void note_error(const char *text) {
	note(text);
	const char *home = getenv("HOME");
	if (home == NULL) return;
	char script[2048];
	snprintf(script, sizeof(script),
		"osascript -e 'display dialog \"%s\" with title \"PSD2Live\" buttons {\"OK\"} default button 1'", text);
	system(script);
}

static char *unquote(const char *input) {
	size_t length = strlen(input);
	while (length > 0 && (input[length - 1] == '\n' || input[length - 1] == '\r' || input[length - 1] == ' '))
		length--;
	size_t start = 0;
	while (start < length && (input[start] == ' ' || input[start] == '\t')) start++;
	if (start < length && input[start] == '"') {
		char *out = malloc(length + 1);
		if (out == NULL) return NULL;
		size_t count = 0;
		for (size_t i = start + 1; i < length; i++) {
			if (input[i] == '"') break;
			if (input[i] == '\\' && i + 1 < length) out[count++] = input[++i];
			else out[count++] = input[i];
		}
		out[count] = 0;
		return out;
	}
	char *out = malloc(length - start + 1);
	if (out == NULL) return NULL;
	memcpy(out, input + start, length - start);
	out[length - start] = 0;
	return out;
}

static void add_option(char ***options, size_t *count, size_t *capacity, char *value) {
	if (value == NULL) return;
	if (*count == *capacity) {
		*capacity = *capacity == 0 ? 32 : *capacity * 2;
		char **grown = realloc(*options, *capacity * sizeof(char *));
		if (grown == NULL) return;
		*options = grown;
	}
	(*options)[(*count)++] = value;
}

static int option_starts(char **options, size_t count, const char *prefix) {
	size_t length = strlen(prefix);
	for (size_t i = 0; i < count; i++) {
		if (strncmp(options[i], prefix, length) == 0) return 1;
	}
	return 0;
}

/** jpackage writes `$APPDIR` for the directory that holds the jars. */
static char *expand_app_dir(const char *value, const char *app_dir) {
	const char *token = "$APPDIR";
	size_t token_length = strlen(token);
	size_t extra = 0;
	for (const char *scan = value; (scan = strstr(scan, token)) != NULL; scan += token_length) extra++;
	char *out = malloc(strlen(value) + extra * strlen(app_dir) + 1);
	if (out == NULL) return NULL;
	char *write = out;
	const char *read = value;
	const char *found;
	while ((found = strstr(read, token)) != NULL) {
		memcpy(write, read, (size_t)(found - read));
		write += found - read;
		memcpy(write, app_dir, strlen(app_dir));
		write += strlen(app_dir);
		read = found + token_length;
	}
	strcpy(write, read);
	return out;
}

static char *jni_class_name(const char *dotted) {
	char *name = strdup(dotted);
	if (name == NULL) return NULL;
	for (char *cursor = name; *cursor != 0; cursor++) {
		if (*cursor == '.') *cursor = '/';
	}
	return name;
}

static int libjvm_exists(const char *home) {
	char path[4096];
	snprintf(path, sizeof(path), "%s/lib/server/libjvm.dylib", home);
	return access(path, R_OK) == 0;
}

/** A packaged app keeps its runtime inside the bundle. A local build points at one with Resources/java.home. */
static char *bundled_java_home(const char *resources) {
	const char *suffixes[] = {"/../runtime/Contents/Home", "/../runtime/Home", "/../runtime"};
	for (size_t i = 0; i < sizeof(suffixes) / sizeof(suffixes[0]); i++) {
		char home[4096];
		snprintf(home, sizeof(home), "%s%s", resources, suffixes[i]);
		if (!libjvm_exists(home)) continue;
		char resolved[4096];
		if (realpath(home, resolved) == NULL) return strdup(home);
		return strdup(resolved);
	}
	return NULL;
}

static char *find_config(const char *resources) {
	char directory[4096];
	snprintf(directory, sizeof(directory), "%s/../app", resources);
	DIR *handle = opendir(directory);
	if (handle == NULL) return NULL;
	char *found = NULL;
	struct dirent *entry;
	while ((entry = readdir(handle)) != NULL) {
		size_t length = strlen(entry->d_name);
		if (length < 5 || strcmp(entry->d_name + length - 4, ".cfg") != 0) continue;
		char path[4096];
		snprintf(path, sizeof(path), "%s/%s", directory, entry->d_name);
		found = strdup(path);
		break;
	}
	closedir(handle);
	return found;
}

struct JavaStart {
	JavaVM *vm;
	jclass main_class;
	jmethodID main_method;
	jobjectArray args;
};

static void *run_editor(void *argument) {
	struct JavaStart *start = argument;
	JNIEnv *env = NULL;
	if ((*start->vm)->AttachCurrentThread(start->vm, (void **)&env, NULL) != JNI_OK) return NULL;
	jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
	jclass loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
	jobject thread = (*env)->CallStaticObjectMethod(env, thread_class,
		(*env)->GetStaticMethodID(env, thread_class, "currentThread", "()Ljava/lang/Thread;"));
	jobject loader = (*env)->CallStaticObjectMethod(env, loader_class,
		(*env)->GetStaticMethodID(env, loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;"));
	(*env)->CallVoidMethod(env, thread,
		(*env)->GetMethodID(env, thread_class, "setContextClassLoader", "(Ljava/lang/ClassLoader;)V"), loader);
	(*env)->CallStaticVoidMethod(env, start->main_class, start->main_method, start->args);
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
	(*start->vm)->DetachCurrentThread(start->vm);
	CFRunLoopStop(CFRunLoopGetMain());
	return NULL;
}

static char *read_line_file(const char *path) {
	FILE *file = fopen(path, "r");
	if (file == NULL) return NULL;
	char buffer[8192];
	if (fgets(buffer, sizeof(buffer), file) == NULL) {
		fclose(file);
		return NULL;
	}
	fclose(file);
	return unquote(buffer);
}

int psd2live_launch(const char *resources, int argc, char **argv) {
	const char *home = getenv("HOME");
	if (home != NULL) {
		char dir[1024];
		snprintf(dir, sizeof(dir), "%s/Library/Logs/PSD2Live", home);
		mkdir(dir, 0755);
		snprintf(log_path, sizeof(log_path), "%s/launch.log", dir);
		chdir(home);
	}
	note("launch");

	char java_home_path[4096];
	snprintf(java_home_path, sizeof(java_home_path), "%s/java.home", resources);
	char *java_home = read_line_file(java_home_path);
	if (java_home == NULL || !libjvm_exists(java_home)) {
		free(java_home);
		java_home = bundled_java_home(resources);
	}
	if (java_home == NULL) {
		note_error("This Mac app is missing its Java runtime.");
		return 1;
	}

	char libjvm[4096];
	snprintf(libjvm, sizeof(libjvm), "%s/lib/server/libjvm.dylib", java_home);
	void *library = dlopen(libjvm, RTLD_NOW | RTLD_GLOBAL);
	if (library == NULL) {
		note_error(dlerror() != NULL ? dlerror() : "Could not load libjvm.");
		return 1;
	}
	typedef jint (*CreateJavaVM)(JavaVM **, void **, void *);
	CreateJavaVM create = (CreateJavaVM)dlsym(library, "JNI_CreateJavaVM");
	if (create == NULL) {
		note_error("JNI_CreateJavaVM is missing.");
		return 1;
	}

	char **options = NULL;
	size_t count = 0;
	size_t capacity = 0;
	char *main_class = NULL;
	char args_path[4096];
	snprintf(args_path, sizeof(args_path), "%s/jvm.args", resources);
	FILE *args_file = fopen(args_path, "r");
	char *app_dir = NULL;
	if (args_file != NULL) {
		char *line = malloc(1024 * 1024);
		if (line == NULL) return 1;
		while (fgets(line, 1024 * 1024, args_file) != NULL) {
			char *value = unquote(line);
			if (value == NULL || value[0] == 0) {
				free(value);
				continue;
			}
			if (main_class != NULL) {
				char *stored = main_class;
				/* -cp is a launcher flag. The VM only takes the classpath as a property. */
				if (strcmp(stored, "-cp") == 0 || strcmp(stored, "-classpath") == 0) {
					free(stored);
					size_t bytes = strlen(value) + strlen("-Djava.class.path=") + 1;
					stored = malloc(bytes);
					if (stored == NULL) return 1;
					snprintf(stored, bytes, "-Djava.class.path=%s", value);
					free(value);
					value = NULL;
				}
				add_option(&options, &count, &capacity, stored);
			}
			main_class = value;
		}
		free(line);
		fclose(args_file);
	} else {
		char *config_path = find_config(resources);
		if (config_path == NULL) {
			note_error("This Mac app is missing its runtime arguments.");
			return 1;
		}
		char relative_app[4096];
		char resolved_app[4096];
		snprintf(relative_app, sizeof(relative_app), "%s/../app", resources);
		if (realpath(relative_app, resolved_app) != NULL) app_dir = strdup(resolved_app);
		else app_dir = strdup(relative_app);
		FILE *config = fopen(config_path, "r");
		free(config_path);
		if (config == NULL) {
			note_error("This Mac app is missing its runtime arguments.");
			return 1;
		}
		char *line = malloc(1024 * 1024);
		char *classpath = NULL;
		if (line == NULL) return 1;
		while (fgets(line, 1024 * 1024, config) != NULL) {
			char *value = unquote(line);
			if (value == NULL || value[0] == 0 || value[0] == '[') {
				free(value);
				continue;
			}
			char *expanded = app_dir == NULL ? strdup(value) : expand_app_dir(value, app_dir);
			free(value);
			if (expanded == NULL) continue;
			if (strncmp(expanded, "app.mainclass=", 14) == 0) {
				free(main_class);
				main_class = strdup(expanded + 14);
				free(expanded);
			} else if (strncmp(expanded, "app.classpath=", 14) == 0) {
				const char *entry = expanded + 14;
				if (classpath == NULL) classpath = strdup(entry);
				else {
					size_t bytes = strlen(classpath) + strlen(entry) + 2;
					char *joined = malloc(bytes);
					if (joined == NULL) return 1;
					snprintf(joined, bytes, "%s:%s", classpath, entry);
					free(classpath);
					classpath = joined;
				}
				free(expanded);
			} else if (strncmp(expanded, "java-options=", 13) == 0) {
				char *option = strdup(expanded + 13);
				free(expanded);
				add_option(&options, &count, &capacity, option);
			} else {
				free(expanded);
			}
		}
		free(line);
		fclose(config);
		if (classpath != NULL) {
			size_t bytes = strlen(classpath) + strlen("-Djava.class.path=") + 1;
			char *option = malloc(bytes);
			if (option == NULL) return 1;
			snprintf(option, bytes, "-Djava.class.path=%s", classpath);
			free(classpath);
			add_option(&options, &count, &capacity, option);
		}
	}
	if (main_class == NULL || main_class[0] == 0) {
		note_error("The runtime arguments have no main class.");
		return 1;
	}

	const char *extra[] = {
		"-Dpsd2live.macShell=true",
		"-Dapple.awt.application.name=PSD2Live",
		"-Dapple.laf.useScreenMenuBar=true",
		"-Dapple.awt.application.appearance=system",
	};
	for (size_t i = 0; i < sizeof(extra) / sizeof(extra[0]); i++) add_option(&options, &count, &capacity, strdup(extra[i]));
	if (app_dir != NULL && !option_starts(options, count, "-Djava.library.path=")) {
		size_t bytes = strlen(app_dir) + strlen("-Djava.library.path=") + 1;
		char *option = malloc(bytes);
		if (option == NULL) return 1;
		snprintf(option, bytes, "-Djava.library.path=%s", app_dir);
		add_option(&options, &count, &capacity, option);
	}
	char executable[4096];
	uint32_t executable_size = sizeof(executable);
	if (_NSGetExecutablePath(executable, &executable_size) == 0 && !option_starts(options, count, "-Djpackage.app-path=")) {
		size_t bytes = strlen(executable) + strlen("-Djpackage.app-path=") + 1;
		char *option = malloc(bytes);
		if (option == NULL) return 1;
		snprintf(option, bytes, "-Djpackage.app-path=%s", executable);
		add_option(&options, &count, &capacity, option);
	}
	free(app_dir);

	/* The Mac java launcher keeps this thread in the AppKit run loop and runs the program on
	 * another thread. AWT posts the menu-bar startup onto that loop. Running the editor here
	 * instead leaves both sides waiting on each other. */
	JavaVMOption *vm_options = calloc(count, sizeof(JavaVMOption));
	if (vm_options == NULL) return 1;
	for (size_t i = 0; i < count; i++) vm_options[i].optionString = options[i];
	JavaVMInitArgs vm_args;
	memset(&vm_args, 0, sizeof(vm_args));
	vm_args.version = JNI_VERSION_10;
	vm_args.nOptions = (jint)count;
	vm_args.options = vm_options;
	/* The packaged config also carries launcher-only options such as -Xdock:name. */
	vm_args.ignoreUnrecognized = JNI_TRUE;

	JavaVM *vm = NULL;
	JNIEnv *env = NULL;
	jint created = create(&vm, (void **)&env, &vm_args);
	if (created != JNI_OK || env == NULL) {
		char message[128];
		snprintf(message, sizeof(message), "Java failed to start (%d).", (int)created);
		note_error(message);
		return 1;
	}

	char *jni_name = jni_class_name(main_class);
	if (jni_name == NULL) return 1;
	jclass main_class_ref = (*env)->FindClass(env, jni_name);
	free(jni_name);
	if (main_class_ref == NULL) {
		(*env)->ExceptionDescribe(env);
		note_error("Could not find the PSD2Live editor.");
		return 1;
	}
	jmethodID main_method = (*env)->GetStaticMethodID(env, main_class_ref, "main", "([Ljava/lang/String;)V");
	if (main_method == NULL) {
		(*env)->ExceptionDescribe(env);
		note_error("Could not find the editor entry point.");
		return 1;
	}
	int user_count = argc > 0 ? argc - 1 : 0;
	jclass string_class = (*env)->FindClass(env, "java/lang/String");
	jobjectArray user_args = (*env)->NewObjectArray(env, user_count, string_class, NULL);
	for (int i = 0; i < user_count; i++) {
		jstring value = (*env)->NewStringUTF(env, argv[i + 1]);
		(*env)->SetObjectArrayElement(env, user_args, i, value);
	}
	struct JavaStart *start = calloc(1, sizeof(struct JavaStart));
	if (start == NULL) return 1;
	start->vm = vm;
	start->main_class = (*env)->NewGlobalRef(env, main_class_ref);
	start->main_method = main_method;
	start->args = (*env)->NewGlobalRef(env, user_args);
	note(main_class);
	pthread_t thread;
	if (pthread_create(&thread, NULL, run_editor, start) != 0) {
		note_error("Could not start the editor thread.");
		return 1;
	}
	pthread_detach(thread);
	CFRunLoopRun();
	return 0;
}
