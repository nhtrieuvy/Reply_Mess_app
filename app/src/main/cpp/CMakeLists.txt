cmake_minimum_required(VERSION 3.22.1)
project(drivevoice_audio LANGUAGES C)

set(rnnoise_archive "${CMAKE_CURRENT_SOURCE_DIR}/third_party/rnnoise-0.2.tar.gz")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rnnoise_archive}")
file(SHA256 "${rnnoise_archive}" rnnoise_digest)
if(NOT rnnoise_digest STREQUAL "90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37")
    message(FATAL_ERROR "RNNoise 0.2 archive checksum mismatch")
endif()
set(rnnoise_root "${CMAKE_CURRENT_BINARY_DIR}/rnnoise-0.2")
if(NOT EXISTS "${rnnoise_root}/src/rnnoise_data.c")
    file(ARCHIVE_EXTRACT INPUT "${rnnoise_archive}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
endif()

# The v0.2 source archive references an Opus header omitted from the release on ARM.
# Use the RNNoise equivalents, as upstream did in its post-v0.2 portability fixes.
foreach(header vec.h vec_neon.h)
    set(path "${rnnoise_root}/src/${header}")
    file(READ "${path}" original)
    string(REPLACE "#include \"os_support.h\"" "#include \"common.h\"" patched "${original}")
    string(REPLACE "OPUS_CLEAR(" "RNN_CLEAR(" patched "${patched}")
    if(NOT original STREQUAL patched)
        file(WRITE "${path}" "${patched}")
    endif()
endforeach()

add_library(drivevoice_audio SHARED rnnoise_bridge.c
    "${rnnoise_root}/src/denoise.c"
    "${rnnoise_root}/src/rnn.c"
    "${rnnoise_root}/src/pitch.c"
    "${rnnoise_root}/src/kiss_fft.c"
    "${rnnoise_root}/src/celt_lpc.c"
    "${rnnoise_root}/src/nnet.c"
    "${rnnoise_root}/src/nnet_default.c"
    "${rnnoise_root}/src/parse_lpcnet_weights.c"
    "${rnnoise_root}/src/rnnoise_data.c"
    "${rnnoise_root}/src/rnnoise_tables.c"
)
target_include_directories(drivevoice_audio PRIVATE "${rnnoise_root}/include" "${rnnoise_root}/src")
target_compile_features(drivevoice_audio PRIVATE c_std_11)
target_compile_options(drivevoice_audio PRIVATE -O2 -fvisibility=hidden)
target_link_options(drivevoice_audio PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_libraries(drivevoice_audio PRIVATE m)
