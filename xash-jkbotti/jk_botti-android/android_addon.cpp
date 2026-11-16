#include "android_addon.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <android/log.h>

static void mkdir_p(const char *path)
{
	char buf[512];
	snprintf(buf, sizeof(buf), "%s", path);
	for (char *p = buf + 1; *p; p++)
	{
		if (*p != '/')
			continue;
		*p = '\0';
		mkdir(buf, 0775);
		*p = '/';
	}
	mkdir(buf, 0775);
}

void jk_android_install_addon_files(const char *moddir)
{
	char base[256], path[512];
	int written = 0;

	snprintf(base, sizeof(base), "%s/addons/jk_botti", moddir);

	for (const jk_embedded_file_t *f = jk_embedded_files; f->path; f++)
	{
		struct stat st;
		snprintf(path, sizeof(path), "%s/%s", base, f->path);
		if (stat(path, &st) == 0)
			continue;

		char dir[512];
		snprintf(dir, sizeof(dir), "%s", path);
		char *slash = strrchr(dir, '/');
		if (slash)
		{
			*slash = '\0';
			mkdir_p(dir);
		}

		FILE *fp = fopen(path, "wb");
		if (!fp)
		{
			__android_log_print(ANDROID_LOG_WARN, "jk_botti", "can't create %s: %s", path, strerror(errno));
			continue;
		}
		fwrite(f->data, 1, f->size, fp);
		fclose(fp);
		written++;
	}

	if (written)
		__android_log_print(ANDROID_LOG_INFO, "jk_botti", "installed %d default files into %s", written, base);
}
