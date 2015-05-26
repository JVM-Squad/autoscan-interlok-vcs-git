package com.adaptris.vcs.git.api;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.adaptris.core.management.vcs.RevisionHistoryItem;
import com.adaptris.core.management.vcs.VcsException;
import com.adaptris.core.management.vcs.VersionControlSystem;
import com.adaptris.vcs.git.auth.AuthenticationProvider;

public class JGitApi implements VersionControlSystem {

  private static final String IMPL_NAME = "Git";

  private static final String COPY_CLONE_POSTFIX = "_copy_doNotEdit";

  private AuthenticationProvider authenticationProvider;

  public JGitApi() {
  }

  public JGitApi(AuthenticationProvider authenticationProvider) {
    setAuthenticationProvider(authenticationProvider);
  }

  @Override
  public String testConnection(String remoteRepoUrl, File workingCopyUrl) throws VcsException {
    try {
      CloneCommand cloneCommand = Git.cloneRepository().setURI(remoteRepoUrl).setDirectory(workingCopyUrl);
      configureAuthentication(cloneCommand);
      cloneCommand.setNoCheckout(true);
      cloneCommand.call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
    // Maybe we should be able to return some sort of revision number
    return null;
  }

  @Override
  public String checkout(String remoteRepoUrl, File workingCopyUrl) throws VcsException {
    // Now make a copy for our querying purposes.  We will also use this to update from.
    // This copy will always be up to date, regardless of the tag the user may have chosen.
    checkoutCopy(remoteRepoUrl, workingCopyUrl);

    try {
      CloneCommand cloneCommand = Git.cloneRepository().setURI(getLocalCloneCopy(workingCopyUrl).getAbsolutePath())
          .setDirectory(workingCopyUrl);
      configureAuthentication(cloneCommand);
      cloneCommand.call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
    return currentLocalRevision(workingCopyUrl);
  }

  @Override
  public String checkout(String remoteRepoUrl, File workingCopyUrl, String revision) throws VcsException {
    this.checkout(remoteRepoUrl, workingCopyUrl);
    return this.update(workingCopyUrl, revision);
  }

  @Override
  public String update(File workingCopyUrl, String tagName) throws VcsException {
    updateCopy(workingCopyUrl); // get all branches and tags first in our copy
    try {
      CheckoutCommand checkoutCommand = getLocalRepository(workingCopyUrl).checkout().setName(tagName);
      checkoutCommand.call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
    return currentLocalRevision(workingCopyUrl);
  }


  @Override
  public String update(File workingCopyUrl) throws VcsException {
    updateCopy(workingCopyUrl); // get all branches and tags first in our copy
    try {
      Git localRepository = getLocalRepository(workingCopyUrl);
      PullCommand pullCommand = localRepository.pull();
      configureAuthentication(pullCommand);
      pullCommand.call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
    return currentLocalRevision(workingCopyUrl);
  }

  @Override
  public void commit(File workingCopyUrl, String commitMessage) throws VcsException {
    try {
      Git localRepository = getLocalRepository(workingCopyUrl);
      localRepository.commit().setAll(true).setMessage(commitMessage).call();

      // This will push to our copy of the clone.
      PushCommand pushCommand = localRepository.push();
      configureAuthentication(pushCommand);
      pushCommand.call();

      // Now push to the actual GIT server from our copy of the clone.
      pushCopy(workingCopyUrl);
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
  }

  @Override
  public void recursiveAdd(File workingCopyUrl) throws VcsException {
    try {
      getLocalRepository(workingCopyUrl).add().call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
  }

  @Override
  public String getImplementationName() {
    return IMPL_NAME;
  }

  @Override
  public String getLocalRevision(File workingCopyUrl) throws VcsException {
    return currentLocalRevision(workingCopyUrl);
  }

  @Override
  public String getRemoteRevision(String remoteRepoUrl, File workingCopyUrl) throws VcsException {
    updateCopy(workingCopyUrl);
    return currentLocalRevision(getLocalCloneCopy(workingCopyUrl));
  }

  @Override
  public List<RevisionHistoryItem> getRemoteRevisionHistory(String remoteRepoUrl, File workingCopyUrl, int limit) throws VcsException {
    List<RevisionHistoryItem> returnedsRevisions = new ArrayList<>();
    updateCopy(workingCopyUrl); // get all commits etc into our copy, then querty that, without modifying the actual working copy.

    Git localRepositoryCopy = getLocalRepository(getLocalCloneCopy(workingCopyUrl));
    try {
      ObjectId head = localRepositoryCopy.getRepository().resolve(Constants.HEAD);
      Iterable<RevCommit> commits = localRepositoryCopy.log().add(head).setMaxCount(limit).call();

      for(RevCommit commit : commits) {
        RevisionHistoryItem item = new RevisionHistoryItem();
        item.setComment(commit.getFullMessage());
        item.setRevision(commit.getId().getName());
        returnedsRevisions.add(item);
      }
    } catch (IOException | GitAPIException ex) {
      throw new VcsException(ex);
    }
    return returnedsRevisions;
  }

  @SuppressWarnings("rawtypes")
  private void configureAuthentication(TransportCommand command) {
    if(getAuthenticationProvider() != null) {
      if(getAuthenticationProvider().getCredentialsProvider() != null) {
        command.setCredentialsProvider(getAuthenticationProvider().getCredentialsProvider());
      }

      if(getAuthenticationProvider().getTransportInterceptor() != null) {
        command.setTransportConfigCallback(getAuthenticationProvider().getTransportInterceptor());
      }
    }
  }

  private Git getLocalRepository(File localRepoDir) throws VcsException {
    FileRepositoryBuilder builder = new FileRepositoryBuilder();
    Repository repository;
    try {
      repository = builder
          .setWorkTree(localRepoDir)
          .setup()
          .build();
    } catch (IOException e) {
      throw new VcsException(e);
    }

    return new Git(repository);
  }

  private String currentLocalRevision(File workingCopyUrl) throws VcsException {
    Git localRepository = getLocalRepository(workingCopyUrl);
    ObjectId resolvedRevision;
    try {
      String fullBranch = localRepository.getRepository().getFullBranch();
      resolvedRevision = localRepository.getRepository().resolve(fullBranch);
    } catch (RevisionSyntaxException | IOException e) {
      throw new VcsException(e);
    }
    return resolvedRevision.getName();
  }

  /**
   * This creates a copy of the local working repository.
   * It's used to pull all tags, commits etc so we can query it without actually modifying the
   * real working copy url.  We do it this way, because we cannot query the server side, we
   * have to keep a local working copy completely up to date.
   * @param remoteRepoUrl
   * @param workingCopyUrl
   * @throws VcsException
   */
  private void checkoutCopy(String remoteRepoUrl, File workingCopyUrl) throws VcsException {
    try {
      CloneCommand cloneCommand = Git.cloneRepository().setURI(remoteRepoUrl).setDirectory(getLocalCloneCopy(workingCopyUrl));
      configureAuthentication(cloneCommand);
      cloneCommand.call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
  }

  private void updateCopy(File workingCopyUrl) throws VcsException {
    try {
      Git localRepository = getLocalRepository(getLocalCloneCopy(workingCopyUrl));
      PullCommand pullCommand = localRepository.pull();
      configureAuthentication(pullCommand);
      pullCommand.call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
  }

  private File getLocalCloneCopy(File workingCopyFile) {
    File copyCloneFile = null;
    String workingCopyDirName = workingCopyFile.getName();
    File workingCopyFileParent = workingCopyFile.getParentFile();

    copyCloneFile = new File(workingCopyFileParent, workingCopyDirName + COPY_CLONE_POSTFIX);
    return copyCloneFile;
  }

  private void pushCopy(File workingCopyUrl) throws VcsException {
    Git localRepositoryCopy = getLocalRepository(getLocalCloneCopy(workingCopyUrl));
    // This will push our copy of the clone to the GIT server.
    PushCommand pushCommand = localRepositoryCopy.push();
    configureAuthentication(pushCommand);
    try {
      pushCommand.call();
    } catch (GitAPIException e) {
      throw new VcsException(e);
    }
  }

  public AuthenticationProvider getAuthenticationProvider() {
    return authenticationProvider;
  }

  public void setAuthenticationProvider(
      AuthenticationProvider authenticationProvider) {
    this.authenticationProvider = authenticationProvider;
  }

}
