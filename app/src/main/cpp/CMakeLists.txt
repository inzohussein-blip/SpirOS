cmake_minimum_required(VERSION 3.22.1)
project(spiros C CXX)

# Native bridge between Kotlin (com.spiros.app.engine.SpirEngine) and the
# virtual machine engine. QEMU sources land in qemu_core/ during track B1;
# until then this builds the bridge alone so the NDK toolchain is exercised.
add_library(spiros SHARED
    spiros_jni.cpp
)

target_compile_options(spiros PRIVATE -Wall -Wextra -Werror -fstack-protector-strong)
target_compile_definitions(spiros PRIVATE SPIROS_ENGINE_VERSION="0.1.0-stub")
# 16 KB page alignment, required for apps targeting Android 15+ devices.
target_link_options(spiros PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,relro,-z,now")

find_library(log-lib log)
target_link_libraries(spiros ${log-lib})
