#include <iostream>

#include "live2d_renderer.h"

/**
 * Minimal smoke test: create the hidden-window OpenGL context and tear it down.
 * Does not load a model, so it can run without private sample assets.
 */
int main()
{
    std::cout << "Live2D offscreen init smoke test..." << std::endl;

    const int initRes = Live2D_InitOffscreen();
    std::cout << "Live2D_InitOffscreen => " << initRes << std::endl;
    if (initRes == 0)
    {
        const char* err = Live2D_GetLastError();
        std::cout << "ERROR: " << (err && err[0] ? err : "unknown failure") << std::endl;
        Live2D_Shutdown();
        return 1;
    }

    Live2D_Shutdown();
    std::cout << "OK" << std::endl;
    return 0;
}
