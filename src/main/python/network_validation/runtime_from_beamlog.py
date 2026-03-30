import re
from datetime import datetime, timedelta
import sys
import os  # Added os import for path expansion

# Constants for robust rollover detection
# We define late and early hour thresholds to ignore minor, within-day time jumps
LATE_HOUR_THRESHOLD = 22  # 10 PM: Time must be this late or later to be considered pre-midnight
EARLY_HOUR_THRESHOLD = 4  # 4 AM: Time must be this early or earlier to be considered post-midnight


def calculate_simulation_runtime(log_content):
    """
    Reads a BEAM simulation log, identifies wall-clock time entries, and
    calculates the total runtime, correctly handling the transition from
    23:59:59 back to 00:00 as a new day starting.

    This version implements a robust check for a true midnight transition
    (Late hour -> Early hour) to avoid false rollover counts from parallel logging.

    :param log_content: A string containing the entire log file content.
    :return: A tuple (start_time, end_time, total_duration_str, rollovers)
    """
    # Regex to match the wall-clock time at the beginning of each log line
    # Format: HH:MM:SS.mmm (e.g., 03:31:13.191)
    time_pattern = re.compile(r"^(\d{2}:\d{2}:\d{2}\.\d{3})")

    timestamps = []

    # 1. Extract Timestamps
    for line in log_content.splitlines():
        match = time_pattern.match(line)
        if match:
            time_str = match.group(1)
            timestamps.append(time_str)

    if not timestamps:
        print("Error: No valid timestamps found in the log file.")
        return None, None, None, 0

    # Get the first and last recorded wall-clock times
    start_time_str = timestamps[0]
    end_time_str = timestamps[-1]

    # 2. Calculate Total Days by Detecting Robust Rollovers

    # Use the first timestamp as the previous time for comparison
    previous_time = datetime.strptime(start_time_str, "%H:%M:%S.%f")
    total_days = 0

    # Iterate over the timestamps to count rollovers
    for time_str in timestamps:
        try:
            current_time = datetime.strptime(time_str, "%H:%M:%S.%f")

            # If the current time is earlier than the previous time (a jump backward)
            if current_time < previous_time:

                # Robust Rollover Check:
                # Count as a true rollover ONLY if the jump crossed midnight,
                # i.e., from a late hour (>= 22:00) to an early hour (< 04:00).
                if previous_time.hour >= LATE_HOUR_THRESHOLD and current_time.hour < EARLY_HOUR_THRESHOLD:
                    total_days += 1

            previous_time = current_time
        except ValueError:
            # Skip invalid time formats
            continue

    # 3. Calculate Final Duration

    # Convert the last time string to a datetime object (End Time)
    final_time = datetime.strptime(end_time_str, "%H:%M:%S.%f")

    # Calculate the naive difference (assuming they are on the same dummy date)
    # The start_time variable must be defined for this subtraction, initialize it here
    start_time = datetime.strptime(start_time_str, "%H:%M:%S.%f")
    time_difference = final_time - start_time

    # Add the full days that occurred between the start and end of the run
    total_rollover_duration = timedelta(days=total_days)

    # Total duration = (Naive time difference) + (total_days * 24 hours)
    total_duration = time_difference + total_rollover_duration

    # Format output
    total_seconds = total_duration.total_seconds()
    days = int(total_seconds // 86400)
    hours = int((total_seconds % 86400) // 3600)
    minutes = int((total_seconds % 3600) // 60)
    seconds = total_seconds % 60

    duration_str = (
        f"{days} days, {hours} hours, {minutes} minutes, and {seconds:.3f} seconds"
    )

    return start_time_str, end_time_str, duration_str, total_days


# --- Main Execution Block ---

# Check if the log file path is provided as a command-line argument
if len(sys.argv) < 2:
    # If not provided, assume the uploaded file name in the Downloads directory
    log_file_path = "~/Downloads/beamLog--FC07-0-20251120-153210.out"
else:
    log_file_path = sys.argv[1]

try:
    # Use os.path.expanduser to correctly resolve the "~" in the path
    with open(os.path.expanduser(log_file_path), 'r') as f:
        log_content = f.read()
except FileNotFoundError:
    print(f"Error: Log file not found at '{log_file_path}'")
    sys.exit(1)
except Exception as e:
    print(f"An error occurred while reading the file: {e}")
    sys.exit(1)

start_t, end_t, total_runtime, rollovers = calculate_simulation_runtime(log_content)

if total_runtime:
    print("\n--- Simulation Runtime Analysis ---")
    print(f"Detected Start Time (Wall Clock): {start_t}")
    print(f"Detected End Time (Wall Clock):   {end_t}")
    print(f"Detected Day Rollovers (00:00 transition): {rollovers}")
    print("-" * 35)
    print(f"TOTAL WALL-CLOCK RUNTIME: {total_runtime}")
    print("-" * 35)
    print("\nNote: The total runtime calculation uses a robust check to filter out small time jumps")
    print("from parallel logging threads, only counting a new day if the time transitions")
    print(f"from a late hour (>= {LATE_HOUR_THRESHOLD}:00) to an early hour (< {EARLY_HOUR_THRESHOLD}:00).")