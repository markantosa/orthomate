import serial, time, sys

port = 'COM28'
baud = 115200

try:
    ser = serial.Serial(port, baud, timeout=1, dsrdtr=True)
    ser.setDTR(True)
    ser.setRTS(False)
except Exception as e:
    print(f"ERROR: {e}")
    sys.exit(1)

time.sleep(4)  # Wait 3s for device setup() delay + 1s buffer
print(f"Reading ALL output from {port} for 30s...\n")
sys.stdout.flush()

end = time.time() + 30
count = 0
while time.time() < end:
    try:
        line = ser.readline().decode('utf-8', errors='ignore').strip()
    except Exception as e:
        print(f"Read error: {e}")
        break
    if line:
        print(repr(line))
        sys.stdout.flush()
        count += 1

ser.close()
print(f"\nDone. Got {count} lines total.")
