#include "android_addon.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
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

// true if the file at <path> has exactly <size> bytes equal to <data>
static bool file_equals(const char *path, const unsigned char *data, size_t size)
{
	FILE *fp = fopen(path, "rb");
	if (!fp)
		return false;

	bool same = true;
	unsigned char buf[4096];
	size_t off = 0, n;
	while (same && (n = fread(buf, 1, sizeof(buf), fp)) > 0)
	{
		if (off + n > size || memcmp(buf, data + off, n) != 0)
			same = false;
		off += n;
	}
	fclose(fp);
	return same && off == size;
}

void jk_android_install_addon_files(const char *moddir)
{
	char base[256], path[512];
	int written = 0, upgraded = 0;

	snprintf(base, sizeof(base), "%s/addons/jk_botti", moddir);

	for (const jk_embedded_file_t *f = jk_embedded_files; f->path; f++)
	{
		struct stat st;
		snprintf(path, sizeof(path), "%s/%s", base, f->path);
		bool upgrade = false;
		if (stat(path, &st) == 0)
		{
			if (!f->legacy || !file_equals(path, f->legacy, f->legacy_size))
				continue;
			upgrade = true;
		}

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
		if (upgrade)
			upgraded++;
		else
			written++;
	}

	if (written || upgraded)
		__android_log_print(ANDROID_LOG_INFO, "jk_botti", "installed %d default files into %s, upgraded %d", written, base, upgraded);
}

static void add_touch_buttons(void (*server_command)(const char *))
{
	// Free spot of the default Xash3D layout: top row, between the touch
	// editor button (x 0.42-0.50) and spray (x 0.68-0.76).
	// flags 12 = TOUCH_FL_CLIENT (temporary, not saved) | TOUCH_FL_MP
	// last argument 1 = keep the button square on any screen aspect ratio
	server_command("touch_addbutton \"jk_bot_remove\" \"addons/jk_botti/touch/bot_remove\" "
		"\"jk_botti kickbot\" 0.525 0.01 0.595 0.13 255 255 255 255 12 1\n");
	server_command("touch_addbutton \"jk_bot_add\" \"addons/jk_botti/touch/bot_add\" "
		"\"jk_botti addbot\" 0.600 0.01 0.670 0.13 255 255 255 255 12 1\n");
}

// seconds after the local player entered the game at which the buttons are
// (re)added; re-adding a button with the same name replaces it
static const float touch_add_delays[] = { 1.0f, 2.5f, 5.0f, 10.0f, 20.0f, 40.0f };
static int touch_step;
static float touch_entered = -1.0f;

void jk_android_touch_reset(void)
{
	touch_step = 0;
	touch_entered = -1.0f;
}

void jk_android_touch_frame(float time, bool host_in_game, void (*server_command)(const char *))
{
	const int steps = sizeof(touch_add_delays) / sizeof(touch_add_delays[0]);

	if (!host_in_game)
	{
		// left / reconnecting: start over when back in the game
		if (touch_entered >= 0.0f)
			jk_android_touch_reset();
		return;
	}

	if (touch_entered < 0.0f || time < touch_entered) // time restarts on a new map
	{
		touch_step = 0;
		touch_entered = time;
	}

	if (touch_step < steps && time - touch_entered >= touch_add_delays[touch_step])
	{
		add_touch_buttons(server_command);
		touch_step++;
	}
}
