@echo off
rem CycleMap data studio (Streamlit). Opens the browser. Closing this window does not stop running jobs.
cd /d "%~dp0.."
if not exist venv\Scripts\streamlit.exe (
  echo streamlit is not installed in venv. Run:
  echo   venv\Scripts\python.exe -m pip install streamlit
  pause
  exit /b 1
)
venv\Scripts\streamlit.exe run scripts\data_studio.py
