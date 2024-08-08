import os


def meaningful(df):
    return df[df.columns[~df.isnull().all()]]


def find_last_created_dir(directory, level, num=0):
    all_dirs = directories_on_level(directory, level)
    return max(all_dirs, key=os.path.getmtime) if num == 0 \
        else sorted(all_dirs, key=os.path.getmtime, reverse=True)[num]


def directories_on_level(directory, level):
    result = []
    for cur_dir, dirs, files in os.walk(directory):
        if cur_dir[len(directory):].count(os.sep) == level:
            for d in dirs:
                result.append(os.path.join(cur_dir, d))

    return result
