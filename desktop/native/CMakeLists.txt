# Desktop build of the same native LoRa PHY the Android app uses (JNI).
cmake_minimum_required(VERSION 3.16)
project(meshsdr_lora LANGUAGES CXX)

# JNI headers are required to compile native methods, but the library is loaded
# by the JVM and does not link to libjvm or AWT. FindJNI's default AWT/JVM
# checks fail on headless JDKs even though the required headers are installed.
find_package(Java REQUIRED COMPONENTS Development)
find_package(Threads REQUIRED)

get_filename_component(MESHSDR_JAVAC_REALPATH "${Java_JAVAC_EXECUTABLE}" REALPATH)
get_filename_component(MESHSDR_JAVA_BIN "${MESHSDR_JAVAC_REALPATH}" DIRECTORY)
get_filename_component(MESHSDR_JAVA_HOME "${MESHSDR_JAVA_BIN}" DIRECTORY)

set(MESHSDR_JNI_INCLUDE "${MESHSDR_JAVA_HOME}/include")
if(WIN32)
  set(MESHSDR_JNI_PLATFORM_INCLUDE "${MESHSDR_JNI_INCLUDE}/win32")
elseif(APPLE)
  set(MESHSDR_JNI_PLATFORM_INCLUDE "${MESHSDR_JNI_INCLUDE}/darwin")
elseif(UNIX)
  set(MESHSDR_JNI_PLATFORM_INCLUDE "${MESHSDR_JNI_INCLUDE}/linux")
else()
  message(FATAL_ERROR "Unsupported JNI platform for MeshSDR native library")
endif()

if(NOT EXISTS "${MESHSDR_JNI_INCLUDE}/jni.h" OR NOT EXISTS "${MESHSDR_JNI_PLATFORM_INCLUDE}/jni_md.h")
  message(FATAL_ERROR "JNI headers not found under ${MESHSDR_JAVA_HOME}; install a full JDK")
endif()

# Windows: static C++ runtime, so the DLL needs nothing beyond the bundled JVM.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")

set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
add_library(meshsdr_lora SHARED ${CPP}/lora_jni.cpp)
target_compile_features(meshsdr_lora PRIVATE cxx_std_17)
target_compile_definitions(meshsdr_lora PRIVATE LORA_LITE_MAX_SF=12 LORA_LITE_MAX_PAYLOAD=255 _USE_MATH_DEFINES)
target_include_directories(meshsdr_lora PRIVATE
  ${CPP}
  ${CPP}/upstream
  ${MESHSDR_JNI_INCLUDE}
  ${MESHSDR_JNI_PLATFORM_INCLUDE}
)

if(MSVC)
  target_compile_options(meshsdr_lora PRIVATE /O2)
else()
  target_compile_options(meshsdr_lora PRIVATE -O2)
  # Self-contained .so/.dll: no dependency on a particular libstdc++.
  target_link_options(meshsdr_lora PRIVATE -static-libstdc++ -static-libgcc)
endif()

# The decoder diversity runs on worker threads.
target_link_libraries(meshsdr_lora PRIVATE Threads::Threads)
