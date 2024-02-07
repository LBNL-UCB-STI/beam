import sys
import yaml
import docker


def pull_images_from_yaml_file_section(yaml_file_path):
    """
    Pulls Docker images specified in the 'docker_images' section of a YAML file.

    Args:
        yaml_file_path (str): The path to the YAML file.
    """
    # Open the settings.yaml file
    with open(yaml_file_path, 'r') as file:

        # Load the YAML data
        data = yaml.safe_load(file)

        client = None
        try:
            # making docker client for pulling images
            client = docker.from_env()

            # Access the 'docker_images' section
            docker_images = data['docker_images']
            print(f"In {yaml_file_path} file found {len(docker_images)} docker images under 'docker_images' category:")
            print(docker_images)

            for image_name in docker_images:
                image_url = docker_images[image_name]
                if ":" in image_url:
                    print(f"Pulling '{image_name}' by url '{image_url}' ...")
                    client.images.pull(image_url)
                else:
                    print(f"The image '{image_name}' has no tag in url '{image_url}' - skipping.")

            print(f"Pulling images complete!")

        finally:
            if client:
                client.close()


if __name__ == "__main__":
    if len(sys.argv) >1:
        yaml_file_path = sys.argv[1]
        pull_images_from_yaml_file_section(yaml_file_path)
    else:
        print(f"First argument expected to contain the path to the YAML file. Got {len(sys.argv)} arguments.")
