import sys


# checks if original string contains only one substring old and no substring new
# if both conditions are met, then replace substring old with substring new
def replace_substring_if_only_one_exist(original_string, substring_old, substring_new):
    if original_string.count(substring_new) == 0 and original_string.count(substring_old) == 1:
        return original_string.replace(substring_old, substring_new)
    else:
        return original_string


# tries to ensure there are quotes around square brakets
# for construction like -PappArgs="[ .. <arguments> .. ]"
# if there is a quote for square bracket - do nothing.
# if there are more than one square bracket - do nothing.
def fix_quotes_for_app_args(input_args_original):
    input_args_changed = replace_substring_if_only_one_exist(input_args_original, "[", "\"[")
    input_args_changed = replace_substring_if_only_one_exist(input_args_changed, "]", "]\"")

    return input_args_changed


input_args_provided = sys.argv[1]
new_args = fix_quotes_for_app_args(input_args_provided)

print(new_args)
