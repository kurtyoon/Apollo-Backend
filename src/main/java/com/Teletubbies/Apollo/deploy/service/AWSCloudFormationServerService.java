package com.Teletubbies.Apollo.deploy.service;

import com.Teletubbies.Apollo.auth.domain.ApolloUser;
import com.Teletubbies.Apollo.auth.domain.Repo;
import com.Teletubbies.Apollo.auth.repository.RepoRepository;
import com.Teletubbies.Apollo.auth.service.UserService;
import com.Teletubbies.Apollo.core.exception.ApolloException;
import com.Teletubbies.Apollo.core.exception.CustomErrorCode;
import com.Teletubbies.Apollo.credential.domain.Credential;
import com.Teletubbies.Apollo.credential.repository.CredentialRepository;
import com.Teletubbies.Apollo.deploy.component.AwsClientComponent;
import com.Teletubbies.Apollo.deploy.domain.ApolloDeployService;
import com.Teletubbies.Apollo.deploy.dto.request.PostServerDeployRequest;
import com.Teletubbies.Apollo.deploy.dto.response.PostServerDeployResponse;
import com.Teletubbies.Apollo.deploy.repository.AwsServiceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DeleteRepositoryRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static com.Teletubbies.Apollo.core.exception.CustomErrorCode.NOT_FOUND_REPO_ERROR;

@Slf4j
@Service
public class AWSCloudFormationServerService {
    private final CloudFormationClient cloudFormationClient;
    private final S3Client s3Client;
    private final EcrClient ecrClient;
    private final RepoRepository repoRepository;
    private final CredentialRepository credentialRepository;
    private final AwsServiceRepository awsServiceRepository;
    private final UserService userService;

    public AWSCloudFormationServerService(AwsClientComponent awsClientComponent, RepoRepository repoRepository, CredentialRepository credentialRepository, AwsServiceRepository awsServiceRepository, UserService userService) {
        this.cloudFormationClient = awsClientComponent.createCFClient();
        this.s3Client = awsClientComponent.createS3Client();
        this.ecrClient = awsClientComponent.createEcrClient();
        this.repoRepository = repoRepository;
        this.credentialRepository = credentialRepository;
        this.awsServiceRepository = awsServiceRepository;
        this.userService = userService;
    }

    public PostServerDeployResponse saveService(Long userId, PostServerDeployRequest request) {
        String repoName = request.getRepoName();
        String EndPoint = createServerStack(repoName);
        ApolloUser user = userService.getUserById(userId);
        Repo repo = repoRepository.findByRepoName(repoName)
                .orElseThrow(() -> new ApolloException(NOT_FOUND_REPO_ERROR, "Repo 정보가 없습니다."));
        ApolloDeployService apolloDeployService = new ApolloDeployService(user, repo, repoName, EndPoint, "server");
        awsServiceRepository.save(apolloDeployService);
        return new PostServerDeployResponse(repoName, "server", EndPoint);
    }

    public String createServerStack(String repoName) {
        final String templateURL = "https://s3.amazonaws.com/apollo-script/api/cloudformation.yaml";
        Repo repo = repoRepository.findByRepoName(repoName)
                .orElseThrow(() -> new ApolloException(NOT_FOUND_REPO_ERROR, "Repo 정보가 없습니다."));
        Long userId = repo.getApolloUser().getId();
        Credential credential = credentialRepository.findByApolloUserId(userId)
                .orElseThrow(() -> new ApolloException(CustomErrorCode.CREDENTIAL_NOT_FOUND_ERROR, "Credential 정보가 없습니다."));

        CreateStackRequest stackRequest = getCreateStackRequest(repoName, templateURL, repo, credential);
        cloudFormationClient.createStack(stackRequest);
        Output output = getOutput(repoName);
        return output.outputValue();
    }

    private Output getOutput(String repoName) {
        DescribeStacksRequest describeStacksRequest = DescribeStacksRequest.builder().stackName(repoName).build();
        cloudFormationClient.waiter().waitUntilStackCreateComplete(describeStacksRequest);
        DescribeStacksResponse describeStacksResponse = cloudFormationClient.describeStacks(describeStacksRequest);
        return describeStacksResponse.stacks().get(0).outputs().get(0);
    }

    private CreateStackRequest getCreateStackRequest(String repoName, String templateURL, Repo repo, Credential credential) {
        return CreateStackRequest
                .builder()
                .templateURL(templateURL)
                .stackName(repoName)
                .parameters(
                        Parameter.builder().parameterKey("AWSRegion").parameterValue(credential.getRegion()).build(),
                        Parameter.builder().parameterKey("accountId").parameterValue(credential.getAwsAccountId()).build(),
                        Parameter.builder().parameterKey("GithubRepositoryName").parameterValue(repo.getRepoName()).build(),
                        Parameter.builder().parameterKey("RepoLocation").parameterValue(repo.getRepoUrl()).build(),
                        Parameter.builder().parameterKey("RepoLogin").parameterValue(repo.getOwnerLogin()).build(),
                        Parameter.builder().parameterKey("GithubToken").parameterValue(credential.getGithubOAuthToken()).build()
                )
                .capabilitiesWithStrings("CAPABILITY_IAM")
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM")
                .build();
    }

    public void deleteServerStack(Long userId, String stackName) {
        ApolloUser user = userService.getUserById(userId);
        String ecrRepositoryName = getECRRepository(stackName);
        String bucketName = getBucketName(stackName);

        if (ecrRepositoryName != null) {
            deleteECRRepository(ecrRepositoryName);
        } else {
            log.info("해당하는 ECR 리포지토리가 존재하지 않습니다.");
        }

        if (bucketName != null) {
            deleteS3Bucket(bucketName);
            log.info("버킷 삭제 완료");
            deleteCloudFormationStack(stackName);
            log.info("스택 삭제 완료");
        } else {
            log.info("버킷이 존재하지 않습니다.");
        }
        ApolloDeployService service = awsServiceRepository.findByApolloUserAndStackName(user, stackName);
        if (service != null) {
            awsServiceRepository.delete(service);
        }
    }

    private String getBucketName(String stackName) {
        try {
            DescribeStackResourcesRequest request = DescribeStackResourcesRequest.builder()
                    .stackName(stackName)
                    .build();
            DescribeStackResourcesResponse response = cloudFormationClient.describeStackResources(request);
            for (StackResource stackResource : response.stackResources()) {
                if ("AWS::S3::Bucket".equals(stackResource.resourceType())) {
                    log.info("스택에서 해당하는 버킷을 찾았습니다.");
                    return stackResource.physicalResourceId();
                }
            }
        } catch (Exception e) {
            log.info("Error occurred while fetching S3 bucket for stack: " + e.getMessage());
        }
        return null;
    }

    private void deleteS3Bucket(String bucketName) {
        try {
            emptyS3Bucket(bucketName);
            DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder().bucket(bucketName).build();
            s3Client.deleteBucket(deleteBucketRequest);
        } catch (Exception e) {
            log.info("버킷 삭제중에 에러가 발생했습니다.: " + e.getMessage());
        }
    }

    private void emptyS3Bucket(String bucketName) {
        ListObjectVersionsRequest listObjectVersionsRequest = ListObjectVersionsRequest.builder()
                .bucket(bucketName)
                .build();
        ListObjectVersionsResponse listObjectVersionsResponse;

        do {
            listObjectVersionsResponse = s3Client.listObjectVersions(listObjectVersionsRequest);

            for (ObjectVersion objectVersion : listObjectVersionsResponse.versions()) {
                DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectVersion.key())
                        .versionId(objectVersion.versionId())
                        .build();
                s3Client.deleteObject(deleteObjectRequest);
            }

            for (DeleteMarkerEntry deleteMarkerEntry: listObjectVersionsResponse.deleteMarkers()) {
                DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(deleteMarkerEntry.key())
                        .versionId(deleteMarkerEntry.versionId())
                        .build();
                s3Client.deleteObject(deleteObjectRequest);
            }

            listObjectVersionsRequest = listObjectVersionsRequest
                    .toBuilder()
                    .keyMarker(listObjectVersionsResponse.nextKeyMarker())
                    .versionIdMarker(listObjectVersionsResponse.nextVersionIdMarker())
                    .build();
        } while (listObjectVersionsResponse.isTruncated());

        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request
                .builder()
                .bucket(bucketName)
                .build();
        ListObjectsV2Response listObjectsV2Response;

        do {
            listObjectsV2Response = s3Client.listObjectsV2(listObjectsV2Request);
            for (S3Object s3Object: listObjectsV2Response.contents()) {
                DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Object.key())
                        .build();
                s3Client.deleteObject(deleteObjectRequest);
            }
            listObjectsV2Request = listObjectsV2Request.toBuilder().continuationToken(listObjectsV2Response.nextContinuationToken()).build();
        } while (listObjectsV2Response.isTruncated());
    }

    private String getECRRepository(String stackName) {
        try {
            DescribeStackResourcesRequest request = DescribeStackResourcesRequest.builder()
                    .stackName(stackName)
                    .build();
            DescribeStackResourcesResponse response = cloudFormationClient.describeStackResources(request);
            for (StackResource stackResource: response.stackResources()) {
                if ("AWS::ECR::Repository".equals(stackResource.resourceType())) {
                    return stackResource.physicalResourceId();
                }
            }
        } catch (Exception e) {
            log.info("Error occurred while fetching ECR repository for stack: " + e.getMessage());
        }
        return null;
    }

    private void deleteECRRepository(String repositoryName) {
        try {
            DeleteRepositoryRequest deleteRepositoryRequest = DeleteRepositoryRequest.builder()
                    .repositoryName(repositoryName)
                    .force(true)
                    .build();
            ecrClient.deleteRepository(deleteRepositoryRequest);
            log.info("다음 리포지토리를 정상적으로 삭제하였습니다: " + repositoryName);
        } catch (Exception e) {
            log.info("리포지토리 삭제중에 에러가 발생했습니다.: " + e.getMessage());
        }
    }

    private void deleteCloudFormationStack(String stackName) {
        try {
            DeleteStackRequest deleteStackRequest = DeleteStackRequest.builder().stackName(stackName).build();
            cloudFormationClient.deleteStack(deleteStackRequest);
        } catch (Exception e) {
            log.info("스택 삭제중에 에러가 발생했습니다.: " + e.getMessage());
        }
    }
}
