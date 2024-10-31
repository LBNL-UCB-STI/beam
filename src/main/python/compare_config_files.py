import re


def parse_config_file(file_path):
    with open(file_path, 'r') as file:
        content = file.read()
    return parse_config_content(content)


def parse_config_content(content, prefix=''):
    config = {}
    stack = [(config, prefix)]
    current_list = None

    for line in content.split('\n'):
        line = re.sub(r'#.*$', '', line).strip()  # Remove comments and trim
        if not line:
            continue

        if line.startswith('}') or line.startswith(']'):
            stack.pop()
            current_list = None if line.startswith('}') else []
            continue

        if '=' in line:
            key, value = [x.strip() for x in line.split('=', 1)]
            full_key = f"{stack[-1][1]}.{key}" if stack[-1][1] else key
            if current_list is not None:
                current_list.append((full_key, value.strip('"')))
            else:
                stack[-1][0][full_key] = value.strip('"')
        elif line.endswith('{'):
            key = line[:-1].strip()
            full_key = f"{stack[-1][1]}.{key}" if stack[-1][1] else key
            new_dict = {}
            stack[-1][0][full_key] = new_dict
            stack.append((new_dict, full_key))
        elif line.endswith('['):
            key = line[:-1].strip()
            full_key = f"{stack[-1][1]}.{key}" if stack[-1][1] else key
            current_list = []
            stack[-1][0][full_key] = current_list
            stack.append((current_list, full_key))

    return config


def compare_configs(config1, config2):
    all_keys = set(config1.keys()) | set(config2.keys())
    differences = []

    for key in sorted(all_keys):
        if key not in config1:
            differences.append(f"Missing in file1: {key}")
        elif key not in config2:
            differences.append(f"Missing in file2: {key}")
        elif isinstance(config1[key], list) and isinstance(config2[key], list):
            list_diff = compare_lists(config1[key], config2[key], key)
            differences.extend(list_diff)
        elif config1[key] != config2[key]:
            differences.append(f"Different values for {key}:\n  File1: {config1[key]}\n  File2: {config2[key]}")

    return differences


def compare_lists(list1, list2, prefix):
    differences = []
    max_len = max(len(list1), len(list2))

    for i in range(max_len):
        if i >= len(list1):
            differences.append(f"Missing in file1: {prefix}[{i}]")
        elif i >= len(list2):
            differences.append(f"Missing in file2: {prefix}[{i}]")
        else:
            item_diff = compare_dict_or_tuple(list1[i], list2[i], f"{prefix}[{i}]")
            differences.extend(item_diff)

    return differences


def compare_dict_or_tuple(item1, item2, prefix):
    if isinstance(item1, tuple) and isinstance(item2, tuple):
        if item1 != item2:
            return [f"Different values for {prefix}:\n  File1: {item1}\n  File2: {item2}"]
    elif isinstance(item1, dict) and isinstance(item2, dict):
        return compare_configs(item1, item2)
    return []


def main():
    file1_path = 'beam.conf.txt'
    file2_path = 'seattle-base.conf'

    config1 = parse_config_file(file1_path)
    config2 = parse_config_file(file2_path)

    differences = compare_configs(config1, config2)

    print(f"Differences between {file1_path} and {file2_path}:")
    for diff in differences:
        print(diff)
    print(f"\nTotal differences found: {len(differences)}")


if __name__ == "__main__":
    main()