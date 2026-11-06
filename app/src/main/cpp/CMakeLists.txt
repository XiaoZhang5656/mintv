cmake_minimum_required(VERSION 3.18)
project(mintvcrypt C)

# 只编 C，不引 C++ 运行时 —— 减小体积，也避免和别的 so 抢 libc++_shared
set(CMAKE_C_STANDARD 99)

add_library(mintvcrypt SHARED crypt.c)

# 体积优化：这两个 so 只是取个 key，不需要调试符号
target_compile_options(mintvcrypt PRIVATE
        -Os
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections)

target_link_options(mintvcrypt PRIVATE
        -Wl,--gc-sections
        -Wl,--strip-all)
