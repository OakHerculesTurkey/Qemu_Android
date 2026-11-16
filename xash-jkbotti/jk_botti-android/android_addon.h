// jk_botti for Xash3D FWGS on Android: default addon files embedded in the
// plugin, so nothing has to be copied by hand to shared storage, plus the
// on-screen bot buttons.
#ifndef JK_ANDROID_ADDON_H
#define JK_ANDROID_ADDON_H

#include <stddef.h>

typedef struct
{
	const char *path;           // relative to <moddir>/addons/jk_botti
	const unsigned char *data;
	size_t size;
	// optional: an older default of this file; an installed copy that is
	// byte-identical to it was never edited and is upgraded to <data>
	const unsigned char *legacy;
	size_t legacy_size;
} jk_embedded_file_t;

extern const jk_embedded_file_t jk_embedded_files[];

// Writes every embedded file that does not exist yet under
// <moddir>/addons/jk_botti (relative to the engine's working directory, which
// Xash3D sets to its base directory). Existing files are never overwritten,
// except unmodified copies of an older default (see legacy above), so user
// edits and recorded waypoints are kept.
void jk_android_install_addon_files(const char *moddir);

// Listen server only: adds "+" / "-" touch buttons that run
// "jk_botti addbot" / "jk_botti kickbot". Temporary buttons (not saved into
// the user's touch config), shown in multiplayer only.
void jk_android_add_touch_buttons(void (*server_command)(const char *));

#endif
