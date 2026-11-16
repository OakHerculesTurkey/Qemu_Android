# Out-of-tree build of jk_botti (metamod plugin) for Xash3D FWGS on Android.
#
#   cmake -S jk_botti-android -B build -DJK_BOTTI_SRC=/path/to/jk_botti \
#         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28
#
# Output: libjk_botti_mm_android_<arch>.so (Xash3D FWGS library naming).
cmake_minimum_required(VERSION 3.19)
project(jk_botti_android LANGUAGES C CXX)

set(JK_BOTTI_SRC "" CACHE PATH "jk_botti source tree")
if(NOT EXISTS "${JK_BOTTI_SRC}/bot.cpp")
	message(FATAL_ERROR "Set JK_BOTTI_SRC to the jk_botti source tree")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
	set(CMAKE_BUILD_TYPE Release)
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
	set(XASH_ARCH arm64)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
	set(XASH_ARCH armv7l)
elseif(ANDROID_ABI STREQUAL "x86_64")
	set(XASH_ARCH amd64)
elseif(ANDROID_ABI STREQUAL "x86")
	set(XASH_ARCH i386)
elseif(NOT ANDROID AND CMAKE_SIZEOF_VOID_P EQUAL 8)
	# host build (Linux x86-64) for testing against a desktop Xash3D server
	set(XASH_ARCH amd64)
else()
	message(FATAL_ERROR "Unsupported ANDROID_ABI '${ANDROID_ABI}'")
endif()

# --- zlib (bundled, symbols prefixed so they never clash with the system one)
file(GLOB ZLIB_SRC "${JK_BOTTI_SRC}/zlib/*.c")
add_library(jk_zlib STATIC ${ZLIB_SRC})
target_compile_definitions(jk_zlib PRIVATE Z_PREFIX HAVE_UNISTD_H HAVE_STDARG_H)
target_compile_options(jk_zlib PRIVATE -O2 -w)

# --- default config/waypoint files, embedded so the plugin can install them
#     into <basedir>/valve/addons/jk_botti on first start
set(ADDON_DIR "${JK_BOTTI_SRC}/addons/jk_botti")
file(GLOB_RECURSE ADDON_FILES RELATIVE "${ADDON_DIR}"
	"${ADDON_DIR}/*.cfg" "${ADDON_DIR}/*.txt" "${ADDON_DIR}/waypoints/*")
list(FILTER ADDON_FILES EXCLUDE REGEX "^dlls/")
list(SORT ADDON_FILES)
set(EMBED_LIST "${CMAKE_CURRENT_BINARY_DIR}/addon_files.list")
file(WRITE "${EMBED_LIST}" "${ADDON_FILES}")
list(TRANSFORM ADDON_FILES PREPEND "${ADDON_DIR}/" OUTPUT_VARIABLE ADDON_DEPS)
set(EMBED_CPP "${CMAKE_CURRENT_BINARY_DIR}/addon_files.cpp")
add_custom_command(
	OUTPUT "${EMBED_CPP}"
	COMMAND ${CMAKE_COMMAND} -DADDON_DIR=${ADDON_DIR} -DOUT=${EMBED_CPP}
		-DLIST=${EMBED_LIST} -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_files.cmake"
	DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/embed_files.cmake" ${ADDON_DEPS}
	COMMENT "Embedding jk_botti addon files")

# --- the plugin
set(JK_SRC
	bot.cpp bot_chat.cpp bot_client.cpp bot_combat.cpp bot_trace.cpp
	bot_config_init.cpp bot_models.cpp bot_navigate.cpp bot_query_hook.cpp
	bot_query_hook_linux.cpp bot_skill.cpp bot_sound.cpp bot_weapons.cpp
	commands.cpp dll.cpp engine.cpp h_export.cpp random_num.cpp
	safe_snprintf.cpp util.cpp waypoint.cpp)
list(TRANSFORM JK_SRC PREPEND "${JK_BOTTI_SRC}/")

if(ANDROID)
	add_library(jk_botti_mm SHARED ${JK_SRC} android_addon.cpp "${EMBED_CPP}")
else()
	add_library(jk_botti_mm SHARED ${JK_SRC})
endif()
target_include_directories(jk_botti_mm PRIVATE
	"${JK_BOTTI_SRC}/metamod" "${JK_BOTTI_SRC}/common" "${JK_BOTTI_SRC}/dlls"
	"${JK_BOTTI_SRC}/engine" "${JK_BOTTI_SRC}/pm_shared" "${CMAKE_CURRENT_SOURCE_DIR}")

execute_process(COMMAND git -C "${JK_BOTTI_SRC}" rev-parse --short HEAD
	OUTPUT_VARIABLE JK_GIT OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
if(NOT JK_GIT)
	set(JK_GIT unknown)
endif()

target_compile_definitions(jk_botti_mm PRIVATE
	VER_MAJOR=1 VER_MINOR=50 "VER_NOTE=\"git${JK_GIT}-android\""
	"VARIANT_VERSION_SUFFIX=\"_android_${XASH_ARCH}\"")
target_compile_options(jk_botti_mm PRIVATE
	-O2 -g -fno-strict-aliasing -fno-strict-overflow -fvisibility=hidden
	-fno-semantic-interposition
	$<$<COMPILE_LANGUAGE:CXX>:-fno-rtti -fno-exceptions>
	-Wall -Wno-write-strings -Wno-unused-function -Wno-unused-variable)
target_link_libraries(jk_botti_mm PRIVATE jk_zlib m)
if(ANDROID)
	target_link_libraries(jk_botti_mm PRIVATE log)
endif()
# No dependency on libc++_shared.so: the engine process only has what its
# own APK and ours ship, so keep the plugin self-contained.
target_link_options(jk_botti_mm PRIVATE -static-libstdc++ -Wl,--no-undefined
	-Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
if(ANDROID)
	set_target_properties(jk_botti_mm PROPERTIES OUTPUT_NAME "jk_botti_mm_android_${XASH_ARCH}")
else()
	set_target_properties(jk_botti_mm PROPERTIES PREFIX "" OUTPUT_NAME "jk_botti_mm_${XASH_ARCH}")
endif()
