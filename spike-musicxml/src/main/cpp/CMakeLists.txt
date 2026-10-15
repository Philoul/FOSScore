cmake_minimum_required(VERSION 3.22.1)
project(verovio_jni)

set(BUILD_AS_ANDROID_LIBRARY ON CACHE BOOL "" FORCE)
add_subdirectory(${VEROVIO_DIR}/cmake ${CMAKE_CURRENT_BINARY_DIR}/verovio_build)

add_library(verovio_jni SHARED verovio_jni.cpp)
# Same include list as Verovio's own cmake/CMakeLists.txt.
target_include_directories(verovio_jni PRIVATE
        ${VEROVIO_DIR}/include
        ${VEROVIO_DIR}/include/crc
        ${VEROVIO_DIR}/include/midi
        ${VEROVIO_DIR}/include/hum
        ${VEROVIO_DIR}/include/json
        ${VEROVIO_DIR}/include/pugi
        ${VEROVIO_DIR}/include/tuning-library
        ${VEROVIO_DIR}/include/zip
        ${VEROVIO_DIR}/include/vrv
        ${VEROVIO_DIR}/libmei/dist
        ${VEROVIO_DIR}/libmei/addons)
set_target_properties(verovio_jni PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
target_link_options(verovio_jni PRIVATE "-Wl,-z,max-page-size=0x4000")
find_library(log-lib log)
target_link_libraries(verovio_jni verovio ${log-lib})
