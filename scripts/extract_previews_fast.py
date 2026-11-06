import os
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor

FFMPEG = r"C:\Program Files\Jellyfin\Server\ffmpeg.exe"
ASSETS_DIR = r"C:\Users\jcgue\.gemini\antigravity\scratch\remux-player-android-tv\app\src\main\assets\previews"

MOVIES = [
    {
        "id": "raiders",
        "path": r"E:\Jellyfin Library\Movies\Raiders of the Lost Ark (1981).mkv",
        "hdr": True,
        "duration": 6918
    },
    {
        "id": "nightmare",
        "path": r"D:\Jellyfin Library\Movies\The Nightmare Before Christmas (1993).mkv",
        "hdr": True,
        "duration": 4560
    },
    {
        "id": "apocalypse",
        "path": r"D:\Jellyfin Library\Movies\Apocalypse Now (Theatrical Version) (1979).mkv",
        "hdr": False,
        "duration": 8820
    },
    {
        "id": "whiplash",
        "path": r"D:\Jellyfin Library\Movies\Whiplash (2014).mkv",
        "hdr": False,
        "duration": 6360
    },
    {
        "id": "hailmary",
        "path": r"D:\Jellyfin Library\Movies\Project Hail Mary (2026).mkv",
        "hdr": True,
        "duration": 9300
    }
]

NUM_FRAMES = 60

def extract_single_frame(ffmpeg_bin, movie_path, is_hdr, target_sec, out_file):
    if is_hdr:
        # Tonemapping mobius with desat=0 preserves full HDR saturation and highlights (NO "lavado con cloro")
        vf = "zscale=tin=smpte2084:min=bt2020nc:pin=bt2020:t=linear:npl=100,tonemap=mobius,scale=320:180,zscale=t=bt709:m=bt709:p=bt709:r=limited,format=yuv420p"
    else:
        vf = "scale=320:180,format=yuv420p"

    h = int(target_sec // 3600)
    m = int((target_sec % 3600) // 60)
    s = int(target_sec % 60)
    time_str = f"{h:02d}:{m:02d}:{s:02d}"

    cmd = [
        ffmpeg_bin,
        "-ss", time_str,
        "-i", movie_path,
        "-vf", vf,
        "-vframes", "1",
        "-q:v", "3",
        "-y",
        out_file
    ]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def extract_movie(movie):
    movie_id = movie["id"]
    movie_path = movie["path"]
    is_hdr = movie["hdr"]
    duration = movie["duration"]

    out_dir = os.path.join(ASSETS_DIR, movie_id)
    os.makedirs(out_dir, exist_ok=True)

    print(f"Extracting {NUM_FRAMES} frames for {movie_id}...")
    t0 = time.time()

    # Frame 0 starts at 75s (after studio intros, right into actual scene)
    # to avoid showing pitch-black opening screens!
    start_sec = 75.0
    end_sec = max(duration - 180.0, start_sec + 60.0)
    step = (end_sec - start_sec) / (NUM_FRAMES - 1)

    tasks = []
    with ThreadPoolExecutor(max_workers=6) as executor:
        for i in range(NUM_FRAMES):
            target_sec = start_sec + i * step
            out_file = os.path.join(out_dir, f"frame_{i}.jpg")
            tasks.append(executor.submit(extract_single_frame, FFMPEG, movie_path, is_hdr, target_sec, out_file))

        for t in tasks:
            t.result()

    print(f"  Completed {movie_id} in {time.time()-t0:.1f}s")

if __name__ == "__main__":
    t_start = time.time()
    for m in MOVIES:
        extract_movie(m)
    print(f"All movies extracted in {time.time()-t_start:.1f}s")
