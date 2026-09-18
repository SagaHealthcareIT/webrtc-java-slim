cmake_minimum_required(VERSION 3.13)

if(NOT WEBRTC_TOOLCHAIN_FILE STREQUAL "")
    message(STATUS "Toolchain file: ${WEBRTC_TOOLCHAIN_FILE}")
    set(CMAKE_TOOLCHAIN_FILE "${WEBRTC_TOOLCHAIN_FILE}")
endif()

project(webrtc-java)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang" AND LINUX)
    find_program(LLD_PATH ld.lld 
        HINTS /opt/clang/bin /usr/bin /usr/local/bin
        DOC "Path to lld linker"
    )
    if(LLD_PATH)
        message(STATUS "Found LLD: ${LLD_PATH}")
        add_link_options(-fuse-ld=lld)
    else()
        message(WARNING "LLD not found! Linker errors may occur.")
    endif()
    # Every Linux target now links WebRTC's bundled libc++, whose headers require
    # the hardening mode to be defined at compile time (normally injected via
    # libc++'s generated __config_site). Apply it for all Linux arches, not just
    # x64, otherwise the arm cross builds fail in __configuration/hardening.h.
    add_compile_options(-nostdinc++ -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_EXTENSIVE)
    add_link_options(-stdlib=libc++ -v -Wl,--verbose)
endif()

if(UNIX AND NOT APPLE)
    # Apply the sysroot configuration for the actual build
    if(DEFERRED_SYSROOT)
        # Configure sysroot for all subsequent operations
        set(CMAKE_SYSROOT ${DEFERRED_SYSROOT})
        set(CMAKE_FIND_ROOT_PATH ${DEFERRED_SYSROOT})

        # Set pkg-config environment
        set(ENV{PKG_CONFIG_SYSROOT_DIR} "${DEFERRED_SYSROOT}")
        set(ENV{PKG_CONFIG_PATH} "${DEFERRED_SYSROOT}/usr/lib/pkgconfig:${DEFERRED_SYSROOT}/usr/share/pkgconfig")

        # Restrict library and header searches to sysroot only
        set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
        set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
        set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
    endif()

    set(LINUX TRUE)
endif()

if(APPLE)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
    set(SOURCE_TARGET macos)
elseif(LINUX)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
    set(SOURCE_TARGET linux)
elseif(WIN32)
    set(CMAKE_CXX_FLAGS_RELEASE "/MT")
    set(CMAKE_CXX_FLAGS_DEBUG "/MTd")
    set(SOURCE_TARGET windows)

    # The VERSIONINFO resource below is a resource script, not C++.
    enable_language(RC)
endif()

if (CMAKE_CXX_COMPILER_ID STREQUAL Clang AND CMAKE_CXX_COMPILER_FRONTEND_VARIANT STREQUAL "MSVC")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /showFilenames")
endif()

add_subdirectory(dependencies/webrtc)
add_subdirectory(dependencies/jni-voithos)

file(GLOB SOURCES_ROOT "src/*.cpp")
file(GLOB SOURCES_API "src/api/*.cpp")
file(GLOB SOURCES_RTC "src/rtc/*.cpp")

list(APPEND SOURCES
    ${SOURCES_ROOT}
    ${SOURCES_API}
    ${SOURCES_RTC}
)

if(WIN32)
    # Windows application control (AppLocker / WDAC) writes publisher rules against the PE
    # VERSIONINFO resource - ProductName, OriginalFilename, FileVersion. Without it an administrator
    # can only write a hash rule, and a hash rule breaks on every release. Authenticode signing does
    # not add this resource; it has to be compiled in here.
    if(NOT WEBRTC_JAVA_VERSION)
        set(WEBRTC_JAVA_VERSION "0.0.0")
    endif()

    set(WEBRTC_JAVA_DLL_NAME "${PROJECT_NAME}-${OUTPUT_NAME_SUFFIX}.dll")

    # FILEVERSION is four integers; the Gradle version is "major.minor.patch".
    string(REGEX MATCHALL "[0-9]+" WEBRTC_JAVA_VERSION_PARTS "${WEBRTC_JAVA_VERSION}")
    list(APPEND WEBRTC_JAVA_VERSION_PARTS 0 0 0 0)
    list(SUBLIST WEBRTC_JAVA_VERSION_PARTS 0 4 WEBRTC_JAVA_VERSION_PARTS)
    list(JOIN WEBRTC_JAVA_VERSION_PARTS "," WEBRTC_JAVA_VERSION_COMMA)

    message(STATUS "VERSIONINFO: ${WEBRTC_JAVA_DLL_NAME} ${WEBRTC_JAVA_VERSION} (${WEBRTC_JAVA_VERSION_COMMA})")

    configure_file("${CMAKE_CURRENT_SOURCE_DIR}/version.rc.in"
        "${CMAKE_CURRENT_BINARY_DIR}/version.rc" @ONLY)

    list(APPEND SOURCES "${CMAKE_CURRENT_BINARY_DIR}/version.rc")
endif()

add_library(${PROJECT_NAME} SHARED ${SOURCES})

# Introduced since WebRTC branch 7204 and Clang 20.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    if(MSVC)
        # clang-cl on Windows requires the /clang: prefix to avoid interpreting /W... as a warning level flag
        target_compile_options(${PROJECT_NAME} PRIVATE /clang:-Wno-nullability-completeness)
    else()
        target_compile_options(${PROJECT_NAME} PRIVATE -Wno-nullability-completeness)
    endif()
endif()

target_include_directories(${PROJECT_NAME}
    PRIVATE
        include
        include/api
        include/rtc
)

set_target_properties(${PROJECT_NAME} PROPERTIES
    OUTPUT_NAME "${PROJECT_NAME}-${OUTPUT_NAME_SUFFIX}"
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

if(LINUX)
    # Ship no RPATH/RUNPATH. target_link_directories() on the WebRTC build tree otherwise leaks the
    # builder's absolute path into DT_RUNPATH (and a trailing empty element, which is the current
    # directory) - harmless at runtime, because the only NEEDED entries are libc, libm and the
    # loader, but an automatic SCA/EDR finding on exactly the audited hosts this library runs on.
    # Set on the target, not through variables, so it cannot depend on evaluation order.
    set_target_properties(${PROJECT_NAME} PROPERTIES
        SKIP_BUILD_RPATH TRUE
        BUILD_WITH_INSTALL_RPATH TRUE
        BUILD_RPATH_USE_LINK_PATH FALSE
        INSTALL_RPATH_USE_LINK_PATH FALSE
        INSTALL_RPATH ""
    )
endif()

target_link_libraries(${PROJECT_NAME} jni-voithos)
target_link_libraries(${PROJECT_NAME} webrtc)

if(APPLE)
    set_source_files_properties(${SOURCES} PROPERTIES COMPILE_FLAGS "-x objective-c++")
    target_link_options(${PROJECT_NAME} PRIVATE "-ObjC")
    target_link_libraries(${PROJECT_NAME} "-framework Foundation" "-framework AVFoundation" "-framework CoreMedia" "-framework CoreVideo")
elseif(LINUX)
    # Link WebRTC's bundled libc++ on every Linux target (see CUSTOM_LIBCXX in
    # dependencies/webrtc/CMakeLists.txt); the arm cross sysroot's system libc++
    # is too old for the C++20 features WebRTC's sources require.
    set(CXX_LIBS "-static-libgcc -stdlib=libc++ -lc++ -lc++abi")

    target_link_libraries(${PROJECT_NAME} ${CXX_LIBS})
elseif(WIN32)
    target_link_libraries(${PROJECT_NAME})
endif()

install(TARGETS ${PROJECT_NAME}
    RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
    LIBRARY DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
)

if (PROJECT_IS_TOP_LEVEL)
    get_filename_component(TARGET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
    
    set(COMPILE_COMMANDS_SRC "${CMAKE_BINARY_DIR}/compile_commands.json")
    set(COMPILE_COMMANDS_DST "${TARGET_DIR}/compile_commands.json")

    if (WIN32)
        # Windows: Copy instead of symlink to avoid permission issues
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${COMPILE_COMMANDS_SRC}"
                "${COMPILE_COMMANDS_DST}"
        )
        message(STATUS "Copied compile_commands.json to: ${TARGET_DIR}")
    else()
        # Unix: Symlink
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E create_symlink
                "${COMPILE_COMMANDS_SRC}"
                "${COMPILE_COMMANDS_DST}"
        )
        message(STATUS "Created compile_commands.json symlink in: ${TARGET_DIR}")
    endif()
endif()