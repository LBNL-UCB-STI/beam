import itertools
from google_storage_analysis import total_info, get_storage_prefix, print_info_for_all_files, print_info_for_level


# It uses different approaches of getting entries for prefixes mentioned in the arrays below
second_level = ['/', 'beam-core-outputs/', 'pilates-outputs/', 'wheelchair-feb2023/']
third_level = ['output/', 'wheelchair/']
all_files = ['ActivitySimData/', 'beam-core-outputs/', 'debug/', 'nyc/', 'temp/', 'urbansim/']
this_level = ['input/pilates/', 's3://glacier-beam-outputs/pilates-outputs/']

all_different = list(itertools.chain(*[second_level, third_level, all_files, this_level]))


def print_info_for_prefixes(prefixes, skip=False):
    for prefix in prefixes:
        if skip & any(prefix.startswith(item) for item in all_different):
            continue
        size, created = total_info(prefix)
        print(f'{prefix}\t{size}\t{created}')



root_prefix = get_storage_prefix(prefix='')
print_info_for_prefixes(root_prefix.prefixes, skip=True)

second_level_prefixes = [prefix for x in second_level for prefix in get_storage_prefix(x).prefixes]
print_info_for_prefixes(second_level_prefixes)
third_level_prefixes = [prefix
                        for x in third_level
                        for y in get_storage_prefix(x).prefixes
                        for prefix in get_storage_prefix(y).prefixes]
print_info_for_prefixes(third_level_prefixes)
print_info_for_all_files(all_files)
for level in this_level:
    print_info_for_level(level)