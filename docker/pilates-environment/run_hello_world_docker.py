import docker

client = docker.from_env()
container = client.containers.run("hello-world", remove=True, detach=True)
try:
    output = container.attach(stdout=True, stream=False, logs=True)
except docker.errors.NotFound:
    output = "Container already finished, no output available."

print(output)

client.close()

print("done!")
