LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := mupdf_core
LOCAL_SRC_FILES := prebuilt/libmupdf.a
include $(PREBUILT_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := mupdf_third
LOCAL_SRC_FILES := prebuilt/libmupdf-third.a
include $(PREBUILT_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := lrmu
LOCAL_SRC_FILES := mu.c mobi_jni.c $(addprefix libmobi/, buffer.c compression.c debug.c encryption.c index.c memory.c meta.c miniz.c opf.c parse_rawml.c randombytes.c read.c sha1.c structure.c util.c write.c xmlwriter.c)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include $(LOCAL_PATH)/libmobi
LOCAL_CFLAGS := -O2 -fvisibility=hidden -std=gnu11 -w -DUSE_XMLWRITER -DUSE_MINIZ -DUSE_ENCRYPTION -DPACKAGE_VERSION=\"0.12\"
LOCAL_STATIC_LIBRARIES := mupdf_core mupdf_third
LOCAL_LDLIBS := -lm -llog -ldl
LOCAL_LDFLAGS := -Wl,--exclude-libs,ALL -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
