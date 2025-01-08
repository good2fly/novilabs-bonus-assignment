package nlhomework.cdk;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.customresources.AwsCustomResource;
import software.amazon.awscdk.customresources.AwsCustomResourcePolicy;
import software.amazon.awscdk.customresources.AwsSdkCall;
import software.amazon.awscdk.customresources.PhysicalResourceId;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Volume;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.efs.FileSystem;
import software.amazon.awscdk.services.efs.PerformanceMode;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class NovilabsAssignmentCdkStack extends Stack {

    public NovilabsAssignmentCdkStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Create a VPC called "novilab-homework-vpc", 2 AZ, CIDR=10.30.0.0/16
        Vpc vpc = Vpc.Builder.create(this, "NovilabsHomeworkVpc")
                .vpcName("novilabs-homework-vpc")
                .ipAddresses(IpAddresses.cidr("10.30.0.0/16")) // make sure it does not overlap with others
                .maxAzs(2)
                .enableDnsSupport(true)
                .enableDnsHostnames(true)
                .subnetConfiguration(List.of(
                 SubnetConfiguration.builder()
                     .name("novilabs-homework-public-subnet")
                     .subnetType(SubnetType.PUBLIC)
                     .cidrMask(24)
                     .build(),
                 SubnetConfiguration.builder()
                     .name("novilabs-homework-private-subnet")
                     .subnetType(SubnetType.PRIVATE_WITH_EGRESS) // or PRIVATE_ISOLATED if no NAT/$$$
                     .cidrMask(24)
                     .build()
                 ))
                .build();

        // Create ECS cluster
        Cluster cluster = Cluster.Builder.create(this, "NovilabsHomeworkEcsCluster")
                .clusterName("novilabs-homework-ecs")
                .vpc(vpc)
                .build();

        // Security group for the Fargate task itself
        SecurityGroup taskSecurityGroup = SecurityGroup.Builder.create(this, "NovilabsHomeworkTaskSG")
                .vpc(vpc)
                .securityGroupName("novilabs-homework-task-sg")
                .allowAllOutbound(true)
                .build();
        taskSecurityGroup.addIngressRule(
                Peer.anyIpv4(),
                Port.tcp(22)
        );

        // Create a security group for EFS and allow inbound from ECS tasks on port 2049
        SecurityGroup efsSecurityGroup = SecurityGroup.Builder.create(this, "NovilabsHomeworkEfsSG")
                .vpc(vpc)
                .securityGroupName("novilabs-homework-efs-sg")
                .allowAllOutbound(true)
                .build();
        //   EFS must allow inbound from the task SG on NFS port 2049...
        efsSecurityGroup.addIngressRule(
                Peer.securityGroupId(taskSecurityGroup.getSecurityGroupId()),
                Port.tcp(2049),
                "Allow NFS from ECS tasks"
        );

        // EFS file system for the container to use - need to add elasticfilesystem:ClientMount, otherwise task cannot mount EFS
        PolicyStatement fsPolicyStatement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(List.of(new AnyPrincipal()))
                .actions(List.of(
                        "elasticfilesystem:ClientMount",  // this was missing in the default file system pocily for some reason
                        "elasticfilesystem:ClientRootAccess",
                        "elasticfilesystem:ClientWrite"
                ))
                .conditions(Map.of(
                        "Bool", Map.of("elasticfilesystem:AccessedViaMountTarget", "true")
                ))
                .build();

        FileSystem efs = FileSystem.Builder.create(this, "NovilabsHomeworkEfs")
                .fileSystemName("novilabs-homework-efs")
                .vpc(vpc)
                .securityGroup(efsSecurityGroup)
                .performanceMode(PerformanceMode.GENERAL_PURPOSE)
                .encrypted(true)
                .removalPolicy(RemovalPolicy.DESTROY) // or RETAIN in production
                .build();
        efs.addToResourcePolicy(fsPolicyStatement);

        //
        // TaskDefinition and supporting constructs for Fargate with an EFS volume
        //

        // Create Task Execution Role
        Role taskExecutionRole = Role.Builder.create(this, "NovilabsHomeworkTaskExeRole")
                .roleName("novilabs-homework-task-execution-role")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy")))
                .build();
        // Create Task Role
        Role taskRole = Role.Builder.create(this, "NovilabsHomeworkTaskRole")
                .roleName("novilabs-homework-task-role")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AmazonElasticFileSystemClientReadWriteAccess"),
                                         ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                                         ManagedPolicy.fromAwsManagedPolicyName("AmazonS3ReadOnlyAccess")))
                .build();
        // Create the Fargate task definition w/ above roles
        FargateTaskDefinition taskDef = FargateTaskDefinition.Builder.create(this, "NovilabsHomeworkTaskDef")
                .family("novilabs-homework-task")
                .executionRole(taskExecutionRole)
                .taskRole(taskRole)
                .cpu(512) // .5 vCPU
                .memoryLimitMiB(1024)
                .runtimePlatform(RuntimePlatform.builder()
                        .operatingSystemFamily(OperatingSystemFamily.LINUX)
                        .build())
                .build();

        // Add EFS volume
        taskDef.addVolume(Volume.builder()
                .name("NovilabsHomeworkEfsVolume")
                .efsVolumeConfiguration(EfsVolumeConfiguration.builder()
                        .fileSystemId(efs.getFileSystemId())
                        .rootDirectory("/") // mount root of EFS
                        .transitEncryption("ENABLED")
                        .build())
                .build());

        // Create sidecar container to mount the file system, and copy the CSV data file from S3
        ContainerDefinition sidecar = taskDef.addContainer("NovilabsHomeworkSidecar",
                ContainerDefinitionOptions.builder()
                        .containerName("novilabs-homework-container-sidecar")
                        .image(ContainerImage.fromRegistry("amazon/aws-cli:latest"))
                        .command(List.of(
                                "s3",
                                "cp",
                                "s3://novilabs-homework-bucket/novi-labs-java-assignment-data.csv",
                                "/mnt/efs/"))
                        .essential(false)
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .streamPrefix("novilabs-homework")
                                .logRetention(RetentionDays.ONE_DAY)
                                .build()))
                        .build()
        );
        sidecar.addMountPoints(MountPoint.builder()
                .containerPath("/mnt/efs")
                .sourceVolume("NovilabsHomeworkEfsVolume")
                .readOnly(false)
                .build());

        // Create main container referencing the ECR image with the Java app, and mount EFS at /apppath
        IRepository ecrRepo = Repository.fromRepositoryName(this, "MyRepo", "nv-home-assignment");
        ContainerDefinition mainContainer = taskDef.addContainer("NovilabsHomeworkContainer",
                ContainerDefinitionOptions.builder()
                        .containerName("novilabs-homework-container")
                        .image(ContainerImage.fromEcrRepository(ecrRepo, "latest"))
                        .memoryLimitMiB(1024)
                        .cpu(512)  // .5 vCPU
                        .command(List.of("/apppath/novi-labs-java-assignment-data.csv", "/apppath/novi-labs-java-assignment-result.csv"))
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .streamPrefix("novilabs-homework")
                                .logRetention(RetentionDays.ONE_DAY)
                                .build()))
                        .build()
        );

        mainContainer.addMountPoints(MountPoint.builder()
                .containerPath("/apppath")  // mount EFS inside container at /apppath
                .sourceVolume("NovilabsHomeworkEfsVolume")
                .readOnly(false)
                .build());
        // Wait for the sidecar to finish successfully (i.e. copied the file from S3)
        mainContainer.addContainerDependencies(ContainerDependency.builder()
                        .container(sidecar)
                        .condition(ContainerDependencyCondition.SUCCESS)
                .build());

        Instance instance = startEc2Instance(vpc, efs, taskSecurityGroup);

        // TODO this seems flaky, failing to mount the file system - investigate later (works fine from console)
        //AwsCustomResource runTaskResource = runTask(vpc, cluster, taskDef, taskSecurityGroup);
    }

    /**
     * Create an EC2 instance, and mount the file system (to verify results).
     *
     * @param vpc
     * @param fs
     * @param taskSecurityGroup
     * @return
     */
    private Instance startEc2Instance(Vpc vpc, FileSystem fs, SecurityGroup taskSecurityGroup) {
        Role ec2Role = Role.Builder.create(this, "NovilabsHomeworkEc2Role")
                .roleName("novilabs-homework-ec2role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                // Attach AmazonSSMManagedInstanceCore so we can use Session Manager
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonElasticFileSystemClientReadWriteAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3ReadOnlyAccess")
                ))
                .build();

        Instance instance = Instance.Builder.create(this, "NovilabsHomeworkEc2Instance")
                .instanceName("novilabs-homework-instance-4-efs")
                .vpc(vpc)
                .role(ec2Role)
                .securityGroup(taskSecurityGroup)
                .instanceType(InstanceType.of(InstanceClass.T2, InstanceSize.MICRO))
                .machineImage(MachineImage.latestAmazonLinux2())
                .keyPair(KeyPair.fromKeyPairName(this, "VpcTestKeyPair", "vpc-test"))
                .userDataCausesReplacement(true) // for quicker dev turnaround
                // mount the file system under /mnt/efs
                .userData(UserData.custom("""
                        #!/bin/bash
                        echo 'User data starting'
                        yum check-update -y && yum install -y amazon-efs-utils &&
                        mkdir /mnt/efs &&
                        mount -t efs -o tls %s:/ /mnt/efs
                        """.formatted(fs.getFileSystemId())))
                .build();

        // Grant the EC2 instance access to the EFS file system
        fs.getConnections().allowDefaultPortFrom(instance); // is this needed even with the security groups?

        return instance;
    }

    /**
     * Start the ECS task by calling "ecs:RunTask" at deployment time.
     *
     * @param vpc
     * @param cluster
     * @param taskDef
     * @param taskSecurityGroup
     * @return
     */
    private AwsCustomResource runTask(Vpc vpc, Cluster cluster, FargateTaskDefinition taskDef, SecurityGroup taskSecurityGroup) {
        return AwsCustomResource.Builder.create(this, "RunNovilabsHomeworkTask")
                .onCreate(AwsSdkCall.builder()
                        .service("ECS")
                        .action("runTask")
                        .parameters(Map.of(
                                "cluster", cluster.getClusterName(),
                                "launchType", "FARGATE",
                                "taskDefinition", taskDef.getTaskDefinitionArn(),
                                "networkConfiguration", Map.of(
                                        "awsvpcConfiguration", Map.of(
                                                "subnets", vpc.getPrivateSubnets().stream()
                                                        .map(subnet -> subnet.getSubnetId())
                                                        .collect(Collectors.toList()),
                                                // securityGroups => the task SG
                                                "securityGroups", List.of(taskSecurityGroup.getSecurityGroupId()),
                                                // Assign a public IP if needed to pull images or access the internet
                                                "assignPublicIp", "ENABLED"
                                        )
                                )
                        ))
                        // Provide a fixed PhysicalResourceId so it doesn't re-run every deploy
                        .physicalResourceId(PhysicalResourceId.of("NovilabsHomeworkOneOffTask"))
                        .build()
                )
                // Policy allowing this custom resource to call ecs:RunTask + pass roles
                .policy(AwsCustomResourcePolicy.fromStatements(List.of(
                        PolicyStatement.Builder.create()
                                .actions(List.of("ecs:RunTask", "iam:PassRole"))
                                // If you want to restrict resources, you'd specify your cluster ARN & taskDef ARN
                                .resources(List.of("*"))
                                .build()
                )))
                .build();
    }

}
