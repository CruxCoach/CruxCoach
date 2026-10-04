# food-vision — desktop harness for food photo recognition (FEAT-069)

Runs the exact native engine (`androidApp/src/main/cpp/vision/food_vision.cpp`),
prompt and grammar (`androidApp/src/main/assets/foodvision/`) that the app
ships, so prompt changes can be checked on a laptop or server first.

```sh
cmake -S tools/food-vision -B build/food-vision -DCMAKE_BUILD_TYPE=Release
cmake --build build/food-vision -j
build/food-vision/food_vision_cli \
  --model Qwen3.5-2B-Q4_K_M.gguf --mmproj mmproj-F16.gguf \
  --assets androidApp/src/main/assets/foodvision --threads 4 photo1.jpg photo2.jpg
```

The model files and their SHA-256 are listed in
`androidApp/src/main/java/com/cruxcoach/android/foodvision/VisionModels.kt`.
Each image prints one JSON line with the answer and the timings. Photos are
scaled to 640 px on the longest side like in the app. Desktop timings say
nothing about phones.
