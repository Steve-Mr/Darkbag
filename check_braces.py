with open('app/src/main/java/com/android/example/cameraxbasic/fragments/CameraFragment.kt', 'r') as f:
    lines = f.readlines()

balance = 0
for i, line in enumerate(lines):
    for char in line:
        if char == '{':
            balance += 1
        elif char == '}':
            balance -= 1
    if balance < 0:
        print(f"Error: Negative balance at line {i+1}")
        break

if balance != 0:
    print(f"Final balance: {balance}")
else:
    print("Braces are balanced.")
