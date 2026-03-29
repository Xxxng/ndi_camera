cmake_minimum_required(VERSION 3.22.1)

project("ndi_camera")

# NDI Library 설정
add_library(ndi-lib SHARED IMPORTED)
set_target_properties(ndi-lib PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libndi.so)

# Native 소스 빌드
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp)

# 헤더 파일 경로 설정 (현재 폴더에 헤더가 있으므로 . 지정)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR})

# 라이브러리 링크
target_link_libraries(${CMAKE_PROJECT_NAME}
    ndi-lib
    android
    log)
