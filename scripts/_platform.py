"""Standard-library-only process/tool discovery for Windows, macOS and Linux."""
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parent.parent


def gradle_command(app_dir):
    """Command to run with cwd=app_dir."""
    return str(Path(app_dir).resolve() / "gradlew.bat") if sys.platform == "win32" else "./gradlew"


def find_tool(name):
    if name not in ("pmtiles", "osmium"):
        raise ValueError(f"Unsupported tool: {name}")
    found = shutil.which(name)
    if found:
        return found
    pipeline = ROOT / "sandbox" / "vector_test" / "pipeline"
    candidates = ([pipeline / f"{name}-win" / f"{name}.exe", pipeline / f"{name}.exe"]
                  if sys.platform == "win32" else [])
    candidates += [pipeline / name, ROOT / "venv" / "bin" / name]
    for path in candidates:
        if path.is_file() and os.access(path, os.X_OK):
            return str(path)
    return None


def find_jdk():
    """Return a usable JDK home, without running Java or changing os.environ."""
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    if sys.platform == "win32":
        for base in (os.environ.get("ProgramFiles", "C:/Program Files"),
                     os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData/Local"))):
            candidates.extend(Path(base) / "Android" / "Android Studio" / jdk for jdk in ("jbr", "jre"))
    elif sys.platform == "darwin":
        candidates.extend(Path("/Applications/Android Studio.app/Contents") / jdk / "Contents/Home"
                          for jdk in ("jbr", "jre"))
        candidates.append(Path.home() / "Applications/Android Studio.app/Contents/jbr/Contents/Home")
    else:
        candidates.extend(Path(base) / jdk for base in ("/opt/android-studio", "/usr/local/android-studio")
                          for jdk in ("jbr", "jre"))
    suffix = ".exe" if sys.platform == "win32" else ""
    for path in candidates:
        if all((path / "bin" / (tool + suffix)).is_file() and
               os.access(path / "bin" / (tool + suffix), os.X_OK) for tool in ("java", "javac")):
            return path
    return None


def java_environment(env=None):
    """Copy an environment, applying the discovered JDK only to child processes."""
    child = dict(os.environ if env is None else env)
    jdk = find_jdk()
    if jdk is not None:
        child["JAVA_HOME"] = str(jdk)
        child["PATH"] = str(jdk / "bin") + os.pathsep + child.get("PATH", "")
    return child
