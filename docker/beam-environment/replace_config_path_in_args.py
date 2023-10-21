import pathlib
import sys


def replace_path_to_config_in_args(input_args_original, data_root, print_output=False):
    def log(string_to_print):
        if print_output:
            print(string_to_print)

    log(f"Looking for a config file path in args: {input_args_original}")

    config_start = input_args_original.find("production/")
    config_end = input_args_original.find(".conf")
    original_config_path_str = input_args_original[config_start:config_end + 5]

    if len(original_config_path_str) == 0:
        log(f"Was not able to find config file path in provided args. Returning original args.")
        return input_args_original

    config = pathlib.Path(original_config_path_str)
    log(f"Config file path found: {str(config)}")

    if config.is_file():
        log(f"Config found by original path: {str(config)}.")
        return input_args_original

    log(f" .. original path '{str(config)}' does not exist.")

    while len(config.parents) > 0:
        config_changed = pathlib.Path(data_root, config)

        if config_changed.is_file():
            log(f"Config found by path: {str(config_changed)}.")
            input_args_changed = input_args_original.replace(original_config_path_str, str(config_changed))
            return input_args_changed

        log(f" .. path '{str(config_changed)}' does not exist.")

        config = pathlib.Path(*config.parts[1:])

    log(f" .. config was not found in all possible locations!")
    return input_args_original


input_args_provided = sys.argv[1]
data_root_provided = sys.argv[2]

print_debug = False
if len(sys.argv) > 3 and sys.argv[3].lower() == 'true':
    print_debug = True

new_args = replace_path_to_config_in_args(input_args_provided, data_root_provided, print_debug)

print(new_args)
