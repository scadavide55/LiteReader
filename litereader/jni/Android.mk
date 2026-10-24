LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := lrdec
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libjpeg
LOCAL_SRC_FILES := decoder.c $(addprefix libjpeg/, jaricom.c jdapimin.c jdapistd.c jdarith.c jdatasrc.c jdcoefct.c jdcolor.c jddctmgr.c jdhuff.c jdinput.c jdmainct.c jdmarker.c jdmaster.c jdmerge.c jdpostct.c jdsample.c jdtrans.c jerror.c jidctflt.c jidctfst.c jidctint.c jmemmgr.c jmemnobs.c jquant1.c jquant2.c jutils.c jcomapi.c)
LOCAL_CFLAGS := -O3 -ffast-math -fvisibility=hidden -DANDROID
LOCAL_ARM_MODE := arm
LOCAL_ARM_NEON := true
LOCAL_LDLIBS := -lz
include $(BUILD_SHARED_LIBRARY)
