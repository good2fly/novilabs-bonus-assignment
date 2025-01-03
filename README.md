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
* Create EC2 instance in one of the private subnets the mount targets are
  * With role that has AmazonSSMManagedInstanceCore (e.g. rr-ssm-and-ecr-access-role)
  * With the task's security group (novilabs-homework-task-sg)
* Connect via session manager
  * `sudo su ec2-user`
  * `sudo mkdir /mnt/efs`
  * `sudo mount -t nfs4 -o nfsvers=4.1 fs-0d174893c4ecda15b.efs.us-west-1.amazonaws.com:/ /mnt/efs`
  * Add CSV

(alternative is to copy from S3 bucket)

## Useful commands

* `mvn package`     compile and run tests
* `cdk ls`          list all stacks in the app
* `cdk synth`       emits the synthesized CloudFormation template
* `cdk deploy`      deploy this stack to your default AWS account/region
* `cdk diff`        compare deployed stack with current state
* `cdk docs`        open CDK documentation
