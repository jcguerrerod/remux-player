import os
import subprocess
import json
import time

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

def format_time(seconds):
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    return f"{h:02d}:{m:02d}:{s:02d}"

def extract_movie_frames(movie, num_frames=60):
    movie_id = movie["id"]
    movie_path = movie["path"]
    is_hdr = movie["hdr"]
    duration = movie["duration"]

    out_dir = os.path.join(ASSETS_DIR, movie_id)
    os.makedirs(out_dir, exist_ok=True)

    print(f"\n==========================================")
    print(f"Extracting {num_frames} frames for {movie_id} (HDR={is_hdr})")
    print(f"File: {movie_path}")
    print(f"==========================================")

    start_sec = 40.0
    end_sec = max(duration - 120.0, start_sec + 60.0)
    step = (end_sec - start_sec) / max(num_frames - 1, 1)

    t0 = time.time()
    for i in range(num_frames):
        target_sec = start_sec + i * step
        time_str = format_time(target_sec)
        out_file = os.path.join(out_dir, f"frame_{i}.jpg")

        if is_hdr:
            # Verified tonemapping filter: rich, vibrant, zero wash-out ("sin cloro")
            vf = "zscale=tin=smpte2084:min=bt2020nc:pin=bt2020:t=linear:npl=100,tonemap=mobius:desat=0.5,scale=320:180,zscale=t=bt709:m=bt709:p=bt709:r=limited,format=yuv420p"
        else:
            vf = "scale=320:180,format=yuv420p"

        cmd = [
            FFMPEG,
            "-ss", time_str,
            "-i", movie_path,
            "-vf", vf,
            "-vframes", "1",
            "-q:v", "3",
            "-y",
            out_file
        ]

        # Run ffmpeg quietly
        proc = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if proc.returncode != 0:
            print(f"  [Error] Frame {i} at {time_str} failed")
        elif i % 10 == 0 or i == num_frames - 1:
            print(f"  Frame {i}/{num_frames - 1} at {time_str} extracted successfully")

    elapsed = time.time() - t0
    print(f"Finished {movie_id} in {elapsed:.1f}s")

if __name__ == "__main__":
    for m in MOVIES:
        if os.path.exists(m["path"]):
            extract_movie_frames(m, num_frames=60)
        else:
            print(f"WARNING: Movie file not found: {m['path']}")
