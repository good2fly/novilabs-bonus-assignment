# Welcome to your CDK Java project!

## Overview
The general approach chosen is as follows:
* Build a VPC with 2 AZs. 2 private, 2 public subnets.
* Build ECS cluster
* Build EFS file system (to read the CSV file from and write the results into)
* Build ECS task definition for Fargate with image built in Assignment 2, and EFS volume mounted to /apppath
* No ECS service needed, as it is a one-off, single-run task


## Run Task
Currently the task is launched right away after the infrastructure setup, from CDK. To run the task manually,
run it from the console, or CLI, something like
```
aws ecs run-task \
    --cluster novilabs-homework-ecs \
    --launch-type FARGATE \
    --task-definition novilabs-homework-task \
    --network-configuration "awsvpcConfiguration={...}" ...
```
## Get Content onto the Volume
The ECS task definition has a sidecar container that copies the source document
(`novi-labs-java-assignment-data.csv` by default) from an S3 bucket onto the mounted
EFS volume. Currently, if a different input file is needed, the steps are:
- Upload the file into S3, take note of the URI
- Update the CDK script:
  - Change the S3 URI (the sidecar command)
  - Update the file name in both the main and sidecar container, if needed  

An EC2 instance is created during deployment that mounts the file system, this
is provided so we can check the result, but is not really necessary for the assignment. 

## Useful commands

* `mvn package`     compile and run tests
* `cdk ls`          list all stacks in the app
* `cdk synth`       emits the synthesized CloudFormation template
* `cdk deploy`      deploy this stack to your default AWS account/region
* `cdk diff`        compare deployed stack with current state
* `cdk docs`        open CDK documentation
