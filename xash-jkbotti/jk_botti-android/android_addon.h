// jk_botti for Xash3D FWGS on Android: default addon files embedded in the
// plugin, so nothing has to be copied by hand to shared storage.
#ifndef JK_ANDROID_ADDON_H
#define JK_ANDROID_ADDON_H

#include <stddef.h>

typedef struct
{
	const char *path;           // relative to <moddir>/addons/jk_botti
	const unsigned char *data;
	size_t size;
} jk_embedded_file_t;

extern const jk_embedded_file_t jk_embedded_files[];

// Writes every embedded file that does not exist yet under
// <moddir>/addons/jk_botti (relative to the engine's working directory, which
// Xash3D sets to its base directory). Existing files are never overwritten,
// so user edits and recorded waypoints are kept.
void jk_android_install_addon_files(const char *moddir);

#endif
