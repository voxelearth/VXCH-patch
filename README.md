# Voxel Earth — VXCH Patch
**VoxelEarth monorepo with consistent versions:** https://github.com/ryanhlewis/VoxelEarth

> ⚠️ **Temporary diverged patch.** This repo carries an experimental **VXCH** voxelization path (target ≈5× speedup). It is intended for testing/benchmarks and will be merged back into the main project once validated.

## What’s here
- Same overall pipeline patterns as the monorepo, with an experimental fast path.
- CUDA voxelizer binary compatible with our Minecraft + web flows.

## Build the GPU voxelizer (WSL2 Ubuntu + NVIDIA GPU)
1) **Install CUDA (WSL2 Ubuntu):**  
   https://developer.nvidia.com/cuda-downloads?target_os=Linux&target_arch=x86_64&Distribution=WSL-Ubuntu&target_version=2.0&target_type=deb_local

2) **Environment + sanity check**
```bash
export PATH=/usr/local/cuda/bin:$PATH
export LD_LIBRARY_PATH=/usr/local/cuda/lib64:$LD_LIBRARY_PATH
source ~/.bashrc
nvcc --version
```

3) **Dependencies layout**
```bash
cp -r ~/voxelearth/trimesh2/ ~
cp -r ~/voxelearth/cuda_voxelizer/ ~
sudo apt install -y libgl-dev libglu-dev libxi-dev
```

4) **Google Draco**
```bash
cd ~
git clone https://github.com/google/draco.git
cd draco
mkdir build && cd build
cmake ..
make

cp -r ~/draco/src/draco ~/trimesh2/include/
cp -r ~/draco/build/draco/* ~/trimesh2/include/draco/
```

5) **Trimesh2**
```bash
cd ~/trimesh2
chmod 777 copyFiles.sh
./copyFiles.sh
```

6) **cuda_voxelizer**
```bash
cd ~/cuda_voxelizer
chmod 777 build.sh
./build.sh

chmod 777 build/cuda_voxelizer
./build/cuda_voxelizer -h
```

## Try it on a file
```bash
./build/cuda_voxelizer -f myfile.glb -s 64 -o glb
# Or export JSON for Minecraft dev flows
./build/cuda_voxelizer -f myfile.glb -s 64 -o json
```

## Useful files
- `cuda_voxelizer/src/voxelize.cu` — CUDA shader: voxelization + color assignment.
- `cuda_voxelizer/src/util_io.cpp` — exporters (GLB/JSON etc.).
- `trimesh2/libsrc/TriMesh_io.cc` — ad-hoc GLTF import & texture extraction.

## Acknowledgements
- **ForceFlow** — cuda_voxelizer & TriMesh2.
- **Lucas Dower** — ObjToSchematic.
- **Cesium / Google** — 3D Tiles.
- **Omar Shehata** — viewer inspiration.
