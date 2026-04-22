import serial, time, sys

port = 'COM28'
baud = 115200
duration = 75  # seconds

try:
    ser = serial.Serial(port, baud, timeout=1)
except Exception as e:
    print(f"ERROR: {e}")
    sys.exit(1)

time.sleep(1)
print(f"Reading {port} at {baud} for {duration}s...\n")
sys.stdout.flush()

end = time.time() + duration
count = 0
while time.time() < end:
    try:
        line = ser.readline().decode('utf-8', errors='ignore').strip()
    except Exception as e:
        print(f"Read error: {e}")
        break
    if '[LIVE]' in line:
        print(line)
        sys.stdout.flush()
        count += 1

ser.close()
print(f"\nDone. Captured {count} LIVE lines.")
