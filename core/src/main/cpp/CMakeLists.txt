cmake_minimum_required(VERSION 3.0)

# 获取git hash

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")
execute_process(
  COMMAND git submodule foreach git log -1 --format=%H   
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
  OUTPUT_VARIABLE COMMIT_HASH
)
string(REPLACE "\n" ";" COMMIT_HASH "${COMMIT_HASH}")
list(GET COMMIT_HASH 1 COMMIT_HASH)
string (REGEX REPLACE "[\n\t\r]" "" COMMIT_HASH ${COMMIT_HASH})
string(SUBSTRING ${COMMIT_HASH} 0 7 COMMIT_HASH)
message(STATUS "git hash= ${COMMIT_HASH}")

# 获取分支名称
execute_process(
  COMMAND git submodule foreach git branch -r --contains ${COMMIT_HASH}
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
  OUTPUT_VARIABLE CURRENT_BRANCH
)
string(REPLACE "\n" ";" CURRENT_BRANCH "${CURRENT_BRANCH}")
list(GET CURRENT_BRANCH 1 CURRENT_BRANCH)
string (REGEX REPLACE "origin/" "" CURRENT_BRANCH ${CURRENT_BRANCH})
string (REGEX REPLACE "[\n\t\r]" "" CURRENT_BRANCH ${CURRENT_BRANCH})
#string(SUBSTRING ${CURRENT_BRANCH} 0 8 CURRENT_BRANCH)
message(STATUS "git current branch = ${CURRENT_BRANCH}")

# 获取生成时间
string(TIMESTAMP COMPILE_TIME "%y%m%d")
string (REGEX REPLACE "[\n\t\r]" "" COMPILE_TIME ${COMPILE_TIME})
string(REGEX REPLACE "\"" "" COMPILE_TIME ${COMPILE_TIME})

# 生成版本信息
set(GIT_VERSION "${CURRENT_BRANCH}_${COMMIT_HASH}_${COMPILE_TIME}")
message(STATUS "version info = ${GIT_VERSION}")

# 去除空格
string(REGEX REPLACE "[ ]+" "" GIT_VERSION "${GIT_VERSION}")

execute_process(
  COMMAND git rev-parse --show-toplevel
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  OUTPUT_VARIABLE REPO_ROOT
  OUTPUT_STRIP_TRAILING_WHITESPACE
)

set(CORE_DIR "${REPO_ROOT}/core/src/foss/golang/clash")

execute_process(
  COMMAND git -C "${REPO_ROOT}" rev-parse --short=7 HEAD
  OUTPUT_VARIABLE CMFA_COMMIT
  OUTPUT_STRIP_TRAILING_WHITESPACE
)
string(LENGTH "${CMFA_COMMIT}" CMFA_COMMIT_LEN)
if (CMFA_COMMIT_LEN GREATER 6)
  string(SUBSTRING "${CMFA_COMMIT}" 0 7 CMFA_COMMIT)
else()
  set(CMFA_COMMIT "unknown")
endif()

execute_process(
  COMMAND git -C "${CORE_DIR}" rev-parse --short=7 HEAD
  OUTPUT_VARIABLE CORE_COMMIT
  OUTPUT_STRIP_TRAILING_WHITESPACE
)
string(LENGTH "${CORE_COMMIT}" CORE_COMMIT_LEN)
if (CORE_COMMIT_LEN GREATER 6)
  string(SUBSTRING "${CORE_COMMIT}" 0 7 CORE_COMMIT)
else()
  set(CORE_COMMIT "unknown")
endif()

execute_process(
  COMMAND git -C "${CORE_DIR}" tag --points-at HEAD --sort=-v:refname
  OUTPUT_VARIABLE CORE_VERSION
  OUTPUT_STRIP_TRAILING_WHITESPACE
)
string(REGEX MATCH "^[^\r\n]+" CORE_VERSION "${CORE_VERSION}")
if (CORE_VERSION STREQUAL "")
  execute_process(
    COMMAND git -C "${CORE_DIR}" describe --tags --abbrev=0 HEAD
    OUTPUT_VARIABLE CORE_VERSION
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
  )
endif()
if (CORE_VERSION STREQUAL "")
  set(CORE_VERSION "unknown")
elseif (NOT CORE_VERSION MATCHES "^[vV]")
  set(CORE_VERSION "v${CORE_VERSION}")
endif()

if (CMFA_COMMIT STREQUAL "")
  set(CMFA_COMMIT "unknown")
endif()
if (CORE_COMMIT STREQUAL "")
  set(CORE_COMMIT "unknown")
endif()

message(STATUS "about version = CMFA ${CMFA_COMMIT}, core ${CORE_VERSION} ${CORE_COMMIT}")

# 保存变量到文件
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/version.h @ONLY)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
