from prefect import flow, task


@task
def say(name: str):
    print(f"hello {name}")


@flow(name="hello-flow")
def hello(name: str = "world"):
    say(name)
